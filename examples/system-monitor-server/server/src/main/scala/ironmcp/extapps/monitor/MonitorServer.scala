package ironmcp.extapps.monitor

import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, JsDate, NoArgs, ViewPage}
import ironmcp.protocol.*
import ironmcp.server.*
import oshi.SystemInfo as Oshi
import oshi.hardware.CentralProcessor.TickType

/** The machine as Node's `os` module and systeminformation describe it, read
  * through OSHI. Node's names are kept (`linux x64`, not `Linux amd64`), so
  * the View shows what upstream's shows.
  */
object Machine:
  private lazy val oshi = Oshi()

  /** `os.platform()`. */
  def platform: String =
    val name = sys.props.getOrElse("os.name", "").toLowerCase
    if name.startsWith("linux") then "linux"
    else if name.startsWith("mac") || name.startsWith("darwin") then "darwin"
    else if name.startsWith("windows") then "win32"
    else if name.contains("freebsd") then "freebsd"
    else name

  /** `os.arch()`. */
  def arch: String = sys.props.getOrElse("os.arch", "") match
    case "amd64" | "x86_64" => "x64"
    case "aarch64"          => "arm64"
    case "x86" | "i386"     => "ia32"
    case other              => other

  def info: SystemInfo =
    val cpu = oshi.getHardware.getProcessor
    SystemInfo(
      oshi.getOperatingSystem.getNetworkParams.getHostName,
      s"$platform $arch",
      arch,
      CpuInfo(Option(cpu.getProcessorIdentifier.getName).map(_.trim).filter(_.nonEmpty).getOrElse("Unknown"), cpu.getLogicalProcessorCount),
      MemoryInfo(oshi.getHardware.getMemory.getTotal)
    )

  /** Per-core times as `os.cpus()[i].times`: idle, and user + nice + sys + idle + irq. */
  def cores: List[CpuCore] =
    oshi.getHardware.getProcessor.getProcessorCpuLoadTicks.toList.map { t =>
      def ticks(kind: TickType) = t(kind.getIndex)
      CpuCore(ticks(TickType.IDLE), ticks(TickType.USER) + ticks(TickType.NICE) + ticks(TickType.SYSTEM) + ticks(TickType.IDLE) + ticks(TickType.IRQ))
    }

  /** systeminformation's `mem()`: active is total minus available. */
  def memory: MemoryUse =
    val mem    = oshi.getHardware.getMemory
    val active = mem.getTotal - mem.getAvailable
    MemoryUse(active, math.round(active.toDouble / mem.getTotal * 100), mem.getAvailable)

  def uptime: Double = oshi.getOperatingSystem.getSystemUptime.toDouble

/** Upstream's system-monitor-server: the machine once, then stats the View polls (an app-only tool). */
object MonitorServer:

  val ResourceUri: UiUri = "ui://system-monitor/mcp-app.html"

  def apply(html: String): McpServer[IO] =
    val info = McpTool[NoArgs]("get-system-info", "Returns system information, including hostname, platform, CPU info, and memory.", title = Some("Get System Info"))(
      _ => IO.blocking(Machine.info)
    ).withUi(ResourceUri)

    val poll = McpTool[NoArgs](
      "poll-system-stats",
      "Returns dynamic system metrics for polling: per-core CPU timing, memory usage, and uptime. App-only.",
      title = Some("Poll System Stats")
    )(_ =>
      for
        now   <- IO.realTimeInstant
        stats <- IO.blocking(PollStats(CpuCores(Machine.cores), Machine.memory, Uptime(Machine.uptime), JsDate.iso(now)))
      yield stats
    ).withVisibility(Visibility.App)

    McpServer(
      info = Implementation(name = "System Monitor Server", version = "1.0.0"),
      tools = Some(ToolSet.of(info, poll)),
      resources = Some(UiResources.of[IO](UiResource(ResourceUri, "ui://system-monitor/mcp-app.html", html, Some("System Monitor UI")))),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("system-monitor-server/view").map(apply)

object Main extends ExampleMain("system-monitor-server", _ => MonitorServer.load)
