package ironmcp.extapps.monitor

import cats.effect.{Fiber, IO}
import chartjs.{Chart, ChartConfiguration, ChartData, ChartJs, Dataset, TooltipItem}
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Js, Ui}
import ironmcp.protocol.Implementation
import org.scalajs.dom

import scala.concurrent.duration.*
import scala.scalajs.js

/** Upstream's system monitor: per-core CPU as a stacked area chart, memory as
  * a bar, uptime; polled every two seconds through the app-only tool
  * `poll-system-stats`. Unlike upstream, polling stops when the View is torn
  * down.
  */
object MonitorView extends ExampleView:

  val appInfo: Implementation   = Implementation(name = "System Monitor", version = "1.0.0")
  override def theming: Boolean = false

  private val HistoryLength = 30
  private val Colors = Vector(
    "rgba(59, 130, 246, 0.7)", "rgba(16, 185, 129, 0.7)", "rgba(245, 158, 11, 0.7)", "rgba(239, 68, 68, 0.7)",
    "rgba(139, 92, 246, 0.7)", "rgba(236, 72, 153, 0.7)", "rgba(20, 184, 166, 0.7)", "rgba(249, 115, 22, 0.7)",
    "rgba(34, 197, 94, 0.7)", "rgba(168, 85, 247, 0.7)", "rgba(251, 146, 60, 0.7)", "rgba(74, 222, 128, 0.7)",
    "rgba(96, 165, 250, 0.7)", "rgba(248, 113, 113, 0.7)", "rgba(167, 139, 250, 0.7)", "rgba(244, 114, 182, 0.7)"
  )

  private var info: Option[SystemInfo]           = None
  private var polling: Option[Fiber[IO, Throwable, Nothing]] = None
  private var history                            = Vector.empty[Vector[Double]]
  private var timeLabels                         = Vector.empty[String]
  private var chart: Option[Chart]               = None
  private var previous: Option[List[CpuCore]]    = None

  private def formatBytes(bytes: Double): String =
    val units = Vector("B", "KB", "MB", "GB", "TB")
    var value = bytes
    var unit  = 0
    while value >= 1024 && unit < units.size - 1 do
      value /= 1024
      unit += 1
    s"${Js.fixed(value, 1)} ${units(unit)}"

  private def formatUptime(seconds: Double): String =
    val days    = math.floor(seconds / 86400).toLong
    val hours   = math.floor((seconds % 86400) / 3600).toLong
    val minutes = math.floor((seconds % 3600) / 60).toLong
    val parts   = List(days -> "d", hours -> "h", minutes -> "m").collect { case (n, u) if n > 0 => s"$n$u" }
    if parts.isEmpty then "< 1m" else parts.mkString(" ")

  private def dark = dom.window.matchMedia("(prefers-color-scheme: dark)").matches

  private def initChart(cores: Int): Chart =
    val text = if dark then "#9ca3af" else "#6b7280"
    val grid = if dark then "#374151" else "#e5e7eb"
    val tooltipLabel: js.Function1[TooltipItem, String] = item => s"${item.dataset.label.getOrElse("")}: ${Js.str(item.parsed.y.asInstanceOf[Double])}%"
    val tick: js.Function1[js.Any, String]       = v => s"${Js.str(v.asInstanceOf[Double])}%"
    Chart(
      Dom.byId[dom.HTMLCanvasElement]("cpu-chart"),
      new ChartConfiguration:
        val `type` = "line"
        val data = new ChartData:
          labels = js.Array[String]()
          var datasets = js.Array((0 until cores).map { i =>
            val color = Colors(i % Colors.size)
            new Dataset:
              var data = js.Array[js.Any]()
              label = s"P$i"
              fill = true
              backgroundColor = color
              borderColor = color.replace("0.7", "1")
              borderWidth = 1
              pointRadius = 0
              tension = 0.3
          }*)
        override val options = js.Dynamic.literal(
          responsive = true,
          maintainAspectRatio = false,
          animation = js.Dynamic.literal(duration = 300),
          interaction = js.Dynamic.literal(intersect = false, mode = "index"),
          plugins = js.Dynamic.literal(
            legend = js.Dynamic.literal(
              display = true,
              position = "bottom",
              labels = js.Dynamic.literal(boxWidth = 12, padding = 8, font = js.Dynamic.literal(size = 10), color = text)
            ),
            tooltip = js.Dynamic.literal(enabled = true, callbacks = js.Dynamic.literal(label = tooltipLabel))
          ),
          scales = js.Dynamic.literal(
            x = js.Dynamic.literal(display = false),
            y = js.Dynamic.literal(
              stacked = true,
              min = 0,
              max = cores * 100,
              ticks = js.Dynamic.literal(callback = tick, color = text, font = js.Dynamic.literal(size = 10)),
              grid = js.Dynamic.literal(color = grid)
            )
          )
        )
    )

  private def updateChart(cores: Int): Unit = chart.foreach { c =>
    c.data.labels = js.Array(timeLabels*)
    for i <- 0 until cores do c.data.datasets(i).data = js.Array(history.map(s => s.lift(i).getOrElse(0.0): js.Any)*)
    val currentMax = (history.map(_.sum) :+ 0.0).max
    c.options.scales.y.max = math.min(math.max(currentMax * 1.2, cores * 15.0), cores * 100.0)
    c.update("none")
  }

  private def usage(current: List[CpuCore]): Vector[Double] =
    previous.filter(_.size == current.size) match
      case None => Vector.fill(current.size)(0.0)
      case Some(prev) =>
        current.zip(prev).map { (cur, p) =>
          val total = cur.total - p.total
          if total == 0 then 0.0 else Js.round((1 - (cur.idle - p.idle).toDouble / total) * 100)
        }.toVector

  private def status(text: String, active: Boolean = false, error: Boolean = false): Unit =
    Dom.byId[dom.HTMLElement]("status-text").textContent = text
    val indicator = Dom.byId[dom.HTMLElement]("status-indicator")
    indicator.classList.remove("polling")
    indicator.classList.remove("error")
    if error then indicator.classList.add("error") else if active then indicator.classList.add("polling")

  private def fetchStats(view: McpAppView[IO]): IO[Unit] =
    info.fold(IO.unit) { system =>
      view
        .callServerTool("poll-system-stats")
        .flatMap { result =>
          IO(result.structuredAs[PollStats].foreach { stats =>
            val cores = system.cpu.count
            history = (history :+ usage(stats.cpu.cores)).takeRight(HistoryLength)
            timeLabels = (timeLabels :+ new js.Date().toLocaleTimeString()).takeRight(HistoryLength)
            previous = Some(stats.cpu.cores)
            updateChart(cores)
            val percent = stats.memory.usedPercent
            val fill    = Dom.byId[dom.HTMLElement]("memory-bar-fill")
            fill.style.width = s"$percent%"
            fill.classList.remove("warning")
            fill.classList.remove("danger")
            if percent >= 80 then fill.classList.add("danger") else if percent >= 60 then fill.classList.add("warning")
            Dom.byId[dom.HTMLElement]("memory-percent").textContent = s"$percent%"
            Dom.byId[dom.HTMLElement]("memory-detail").textContent =
              s"${formatBytes(stats.memory.usedBytes.toDouble)} / ${formatBytes(system.memory.totalBytes.toDouble)}"
            Dom.byId[dom.HTMLElement]("info-uptime").textContent = formatUptime(stats.uptime.seconds)
            val time = new js.Date().asInstanceOf[js.Dynamic].toLocaleTimeString("en-US", js.Dynamic.literal(hour12 = false)).asInstanceOf[String]
            status(time, active = true)
          })
        }
        .handleErrorWith(e => IO(dom.console.error("Failed to fetch stats:", e.toString)) >> IO(status("Error", error = true)))
    }

  private def button = Dom.byId[dom.HTMLElement]("poll-toggle-btn")

  private def startPolling(view: McpAppView[IO]): IO[Unit] =
    if polling.isDefined then IO.unit
    else
      IO { button.textContent = "Stop"; button.classList.add("active"); status("Starting...", active = true) } >>
        (fetchStats(view) >> IO.sleep(2.seconds)).foreverM.start.flatMap(f => IO { polling = Some(f) })

  private def stopPolling: IO[Unit] =
    polling.fold(IO.unit) { f =>
      IO { polling = None } >> f.cancel >> IO { button.textContent = "Start"; button.classList.remove("active"); status("Stopped") }
    }

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    IO {
      Chart.register(ChartJs.registerables.toSeq*)
      ui.onClick(button)(if polling.isDefined then stopPolling else startPolling(view))
      dom.window
        .matchMedia("(prefers-color-scheme: dark)")
        .addEventListener("change", (_: dom.Event) =>
          ui.run(IO(info.foreach { system => chart.foreach(_.destroy()); chart = Some(initChart(system.cpu.count)); updateChart(system.cpu.count) }))
        )
    } >> ui.onTeardown(stopPolling) >>
      SafeArea.follow(view, Dom.query[dom.HTMLElement](".main")).surround(
        view.events
          .evalMap {
            case HostEvent.ToolResult(result) =>
              result.structuredAs[SystemInfo].fold(_ => IO.unit, system =>
                IO {
                  info = Some(system)
                  chart.foreach(_.destroy())
                  chart = Some(initChart(system.cpu.count))
                  Dom.byId[dom.HTMLElement]("info-hostname").textContent = system.hostname
                  Dom.byId[dom.HTMLElement]("info-platform").textContent = system.platform
                } >> startPolling(view)
              )
            case _ => IO.unit
          }
          .compile
          .drain
      )
