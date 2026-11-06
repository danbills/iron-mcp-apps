package ironmcp.extapps.basic

import cats.Show
import cats.effect.IO
import io.circe.Codec
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, JsDate, NoArgs, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*

/** `get-time`'s result: the text block is the time itself. */
final case class ServerTime(time: String) derives Codec.AsObject, JsonSchemaOf
object ServerTime:
  given Show[ServerTime] = _.time

/** Upstream's basic-server (the vanilla JS one; its React, Vue, Svelte, Solid
  * and Preact siblings are the same app in other frameworks). The server name
  * is upstream's, so ext-apps' e2e suite finds it.
  */
object BasicServer:

  val ResourceUri: UiUri = "ui://get-time/mcp-app.html"

  def apply(html: String): McpServer[IO] =
    val getTime = McpTool[NoArgs]("get-time", "Returns the current server time as an ISO 8601 string.", title = Some("Get Time"))(_ =>
      IO.realTimeInstant.map(now => ServerTime(JsDate.iso(now)))
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Basic MCP App Server (Vanilla JS)", version = "1.0.0"),
      tools = Some(ToolSet.of(getTime)),
      resources = Some(UiResources.of[IO](UiResource(ResourceUri, "ui://get-time/mcp-app.html", html))),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("basic-server/view").map(apply)

object Main extends ExampleMain("basic-server", BasicServer.load)
