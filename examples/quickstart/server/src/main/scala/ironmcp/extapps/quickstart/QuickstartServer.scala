package ironmcp.extapps.quickstart

import cats.effect.{ExitCode, IO, IOApp}
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleServer, JsDate, NoArgs, ViewPage}
import ironmcp.protocol.*
import ironmcp.server.*

/** Upstream's quickstart: one tool, `get-time`, and the View that shows its result. */
object QuickstartServer:

  val ResourceUri: UiUri = "ui://get-time/mcp-app.html"

  def apply(html: String): McpServer[IO] =
    val getTime = McpTool[NoArgs]("get-time", "Returns the current server time.", title = Some("Get Time"))(_ =>
      IO.realTimeInstant.map(now => CallToolResult(content = List(ContentBlock.Text(JsDate.iso(now)))))
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Quickstart MCP App Server", version = "1.0.0"),
      tools = Some(ToolSet.of(getTime)),
      resources = Some(UiResources.of[IO](UiResource(ResourceUri, "ui://get-time/mcp-app.html", html))),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("quickstart/view").map(apply)

object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    QuickstartServer.load.flatMap(ExampleServer.main(args, _, "quickstart"))
