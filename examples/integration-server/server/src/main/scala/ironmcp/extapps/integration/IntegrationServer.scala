package ironmcp.extapps.integration

import cats.effect.IO
import io.circe.Codec
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, JsDate, NoArgs, SingleResource, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*

/** `get-time`'s result; its text block is the serialized JSON, as upstream's. */
final case class ServerTime(time: String) derives Codec.AsObject, JsonSchemaOf

/** Upstream's integration-server, which ext-apps' e2e suite drives (send
  * message, log, open link), plus a plain resource a View can download by link.
  */
object IntegrationServer:

  val ResourceUri: UiUri = "ui://get-time/mcp-app.html"
  inline val SampleDownloadUri = "resource:///sample-report.txt"

  private val report: IO[ResourceContents] =
    IO.realTimeInstant.map(now =>
      ResourceContents.Text(
        SampleDownloadUri,
        List(
          "Integration Test Server — Sample Report",
          s"Generated: ${JsDate.iso(now)}",
          "",
          "This file was downloaded via MCP ResourceLink.",
          "The host resolved it by calling resources/read on the server."
        ).mkString("\n"),
        Some("text/plain")
      )
    )

  def apply(html: String): McpServer[IO] =
    val getTime = McpTool[NoArgs]("get-time", "Returns the current server time.", title = Some("Get Time"))(_ =>
      IO.realTimeInstant.map(now => ServerTime(JsDate.iso(now)))
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Integration Test Server", version = "1.0.0"),
      tools = Some(ToolSet.of(getTime)),
      resources = Some(
        ResourceProvider.all(
          UiResources.of[IO](UiResource(ResourceUri, "ui://get-time/mcp-app.html", html)),
          SingleResource[IO](Resource(uri = SampleDownloadUri, name = "resource:///sample-report.txt", mimeType = Some("text/plain")), report)
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("integration-server/view").map(apply)

object Main extends ExampleMain("integration-server", IntegrationServer.load)
