package ironmcp.extapps.segmentation

import cats.effect.IO
import chartjs.{ActiveElement, Chart, ChartConfiguration, ChartData, ChartJs, Dataset, TooltipItem}
import io.circe.parser.decode
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Js, Log, Ui}
import ironmcp.protocol.{ContentBlock, Implementation}
import org.scalajs.dom

import scala.concurrent.duration.*
import scala.scalajs.js

/** Upstream's customer segmentation explorer: a Chart.js bubble chart of 250
  * customers, any two metrics on the axes, a third as bubble size, segments
  * toggled from the legend, and a detail panel for the point under the pointer.
  */
object SegmentationView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Customer Segmentation", version = "1.0.0")

  private val log = Log("[APP]")

  private var customers: List[Customer]        = Nil
  private var segments: List[SegmentSummary]   = Nil
  private var chart: Option[Chart]             = None
  private var xAxis                            = "annualRevenue"
  private var yAxis                            = "engagementScore"
  private var sizeMetric                       = "off"
  private var hidden: Set[String]              = Set.empty
  private var selected: Option[Customer]       = None
  private var resolver: Option[dom.HTMLElement] = None

  private def formatValue(value: Double, metric: String): String = metric match
    case "annualRevenue"   => if value >= 1000000 then s"$$${Js.fixed(value / 1000000, 1)}M" else s"$$${Js.fixed(value / 1000, 0)}K"
    case "employeeCount"   => Js.locale(value)
    case "accountAge"      => s"${Js.str(value)}mo"
    case "nps"             => if value >= 0 then s"+${Js.str(value)}" else Js.str(value)
    case _                 => Js.str(value)

  private def visible = customers.filterNot(c => hidden(c.segment))

  private def datasets: js.Array[Dataset] =
    val shown = visible
    val sizeRange =
      Option.when(sizeMetric != "off")(customers.map(_.metric(sizeMetric).toDouble)).map(vs => (vs.min, vs.max))
    def radius(c: Customer) = sizeRange.fold(8.0) { (min, max) =>
      if max == min then 12 else 6 + (c.metric(sizeMetric) - min) / (max - min) * 24
    }
    js.Array(shown.map(_.segment).distinct.map { segment =>
      val color = Segments.colors.getOrElse(segment, "#888888")
      new Dataset:
        var data = js.Array(shown.filter(_.segment == segment).map { c =>
          js.Dynamic.literal(x = c.metric(xAxis).toDouble, y = c.metric(yAxis).toDouble, r = radius(c), customer = c.asInstanceOf[js.Any]): js.Any
        }*)
        label = segment
        backgroundColor = color + "aa"
        borderColor = color
        borderWidth = 1
        hoverBackgroundColor = color
        hoverBorderColor = "#ffffff"
        hoverBorderWidth = 2
    }*)

  /** A CSS colour the canvas can use: `light-dark(...)` resolved through a hidden element. */
  private def resolve(css: String, fallback: String): String =
    if css.isEmpty then fallback
    else if !css.contains("light-dark(") then css
    else
      val el = resolver.getOrElse {
        val div = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
        div.style.position = "absolute"
        div.style.visibility = "hidden"
        div.style.pointerEvents = "none"
        dom.document.body.appendChild(div)
        resolver = Some(div)
        div
      }
      el.style.color = css
      Option(dom.window.getComputedStyle(el).color).filter(_.nonEmpty).getOrElse(fallback)

  private def chartColors: (String, String) =
    val style = dom.window.getComputedStyle(dom.document.documentElement)
    (resolve(style.getPropertyValue("--color-text-secondary").trim, "#6b7280"), resolve(style.getPropertyValue("--color-border-primary").trim, "#e5e7eb"))

  private def customerAt(elements: js.Array[ActiveElement]): Option[Customer] =
    for
      c <- chart
      e <- elements.headOption
    yield c.data.datasets(e.datasetIndex).data(e.index).asInstanceOf[js.Dynamic].customer.asInstanceOf[Customer]

  private def scale(text: String, color: String, grid: String, metric: () => String) =
    val tick: js.Function1[Double, String] = v => formatValue(v, metric())
    js.Dynamic.literal(
      title = js.Dynamic.literal(display = true, text = text, color = color, font = js.Dynamic.literal(size = 11, weight = "bold")),
      ticks = js.Dynamic.literal(color = color, font = js.Dynamic.literal(size = 10), callback = tick),
      grid = js.Dynamic.literal(color = grid)
    )

  private def initChart(): Chart =
    val (text, grid) = chartColors
    val label: js.Function1[TooltipItem, js.Array[String]] = item =>
      val c = item.raw.customer.asInstanceOf[Customer]
      js.Array(
        c.name,
        s"${Metrics.labels(xAxis)}: ${formatValue(c.metric(xAxis).toDouble, xAxis)}",
        s"${Metrics.labels(yAxis)}: ${formatValue(c.metric(yAxis).toDouble, yAxis)}"
      )
    val onClick: js.Function2[js.Any, js.Array[ActiveElement], Unit] = (_, elements) =>
      customerAt(elements).foreach { c => selected = Some(c); detail(c) }
    val onHover: js.Function2[js.Any, js.Array[ActiveElement], Unit] = (_, elements) =>
      if selected.isEmpty then customerAt(elements).fold(resetDetail())(detail)
    Chart(
      Dom.byId[dom.HTMLCanvasElement]("scatter-chart"),
      new ChartConfiguration:
        val `type` = "bubble"
        val data   = new ChartData { var datasets = SegmentationView.datasets }
        override val options = js.Dynamic.literal(
          responsive = true,
          maintainAspectRatio = false,
          animation = js.Dynamic.literal(duration = 300),
          interaction = js.Dynamic.literal(intersect = true, mode = "nearest"),
          plugins = js.Dynamic.literal(
            legend = js.Dynamic.literal(display = false),
            tooltip = js.Dynamic.literal(enabled = true, callbacks = js.Dynamic.literal(label = label))
          ),
          scales = js.Dynamic.literal(x = scale(Metrics.labels(xAxis), text, grid, () => xAxis), y = scale(Metrics.labels(yAxis), text, grid, () => yAxis)),
          onClick = onClick,
          onHover = onHover
        )
    )

  private def updateChart(): Unit = chart.foreach { c =>
    val (text, grid) = chartColors
    c.data.datasets = datasets
    val scales = c.options.scales
    for (axis, metric) <- List("x" -> xAxis, "y" -> yAxis) do
      val s = scales.selectDynamic(axis)
      s.title.text = Metrics.labels(metric)
      s.title.color = text
      s.ticks.color = text
      s.grid.color = grid
    c.update()
  }

  private def renderLegend(ui: Ui): Unit =
    val counts = customers.groupBy(_.segment).view.mapValues(_.size).toMap
    val legend = Dom.byId[dom.HTMLElement]("legend")
    legend.innerHTML = segments.map { s =>
      s"""
        <div class="legend-item ${if hidden(s.name) then "hidden" else ""}" data-segment="${s.name}">
          <span class="legend-dot" style="background: ${s.color}"></span>
          <span class="legend-label">${s.name}</span>
          <span class="legend-count">(${counts.getOrElse(s.name, 0)})</span>
        </div>
      """
    }.mkString
    Dom.queryAll[dom.HTMLElement]("#legend .legend-item").foreach { item =>
      ui.onClick(item)(IO {
        val segment = item.getAttribute("data-segment")
        hidden = if hidden(segment) then hidden - segment else hidden + segment
        renderLegend(ui)
        updateChart()
      })
    }

  private def escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

  private def detail(c: Customer): Unit =
    Dom.byId[dom.HTMLElement]("detail-panel").innerHTML = s"""
    <span class="detail-name">${escape(c.name)}</span>
    <span class="detail-segment ${c.segment.toLowerCase}">${escape(c.segment)}</span>
    <span class="detail-metric"><strong>${formatValue(c.annualRevenue.toDouble, "annualRevenue")}</strong> rev</span>
    <span class="detail-metric"><strong>${c.engagementScore}</strong> engagement</span>
    <span class="detail-metric"><strong>${if c.nps >= 0 then "+" else ""}${c.nps}</strong> NPS</span>
  """

  private def resetDetail(): Unit =
    Dom.byId[dom.HTMLElement]("detail-panel").innerHTML = """<span class="detail-placeholder">Hover over a point to see details</span>"""

  /** Upstream reads the text block, not structuredContent. */
  private def fetchData(view: McpAppView[IO], ui: Ui): IO[Unit] =
    view
      .callServerTool("get-customer-data")
      .flatMap { result =>
        val text = result.content.collect { case ContentBlock.Text(t, _, _) => t }.mkString
        IO.fromEither(decode[CustomerData](text)).flatMap { data =>
          IO {
            customers = data.customers
            segments = data.segments
            if chart.isEmpty then chart = Some(initChart()) else updateChart()
            renderLegend(ui)
          } >> log.info(s"Loaded ${data.customers.size} customers")
        }
      }
      .handleErrorWith(e => log.error("Failed to fetch data:", e))

  private def recreateChart(): Unit = chart.foreach { c => c.destroy(); chart = Some(initChart()) }

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    val wire = IO {
      Chart.register(ChartJs.registerables.toSeq*)
      val setters: List[(String, String => Unit)] =
        List("x-axis" -> ((v: String) => xAxis = v), "y-axis" -> ((v: String) => yAxis = v), "size-metric" -> ((v: String) => sizeMetric = v))
      for (id, set) <- setters do
        val select = Dom.byId[dom.HTMLSelectElement](id)
        ui.on(select, "change")(_ => IO { set(select.value); updateChart() })
      ui.on(dom.document, "click") { e =>
        IO(if e.target.asInstanceOf[dom.Element].closest(".chart-section") == null then { selected = None; resetDetail() })
      }
    }
    // The chart takes the host's colours, so a new theme or new variables redraw it.
    val restyle = view.hostContext.discrete
      .drop(1)
      .evalMap(ctx => IO(if ctx.theme.isDefined || ctx.styles.isDefined then recreateChart()))
    wire >> (IO.sleep(100.millis) >> fetchData(view, ui)).start >>
      SafeArea.follow(view, Dom.query[dom.HTMLElement](".main")).surround(view.events.merge(restyle.drain).compile.drain)
