package ironmcp.extapps.transcript

import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, NoArgs, ViewPage}
import ironmcp.protocol.*
import ironmcp.server.*

/** Upstream's transcript-server: the work is in the View (speech recognition
  * in the browser); the tool only opens it. The View asks the host for the
  * microphone and for clipboard writes.
  */
object TranscriptServer:

  val ResourceUri: UiUri = "ui://transcript/mcp-app.html"

  def apply(html: String): McpServer[IO] =
    val transcribe = McpTool[NoArgs](
      "transcribe",
      "Opens a live speech transcription interface using the Web Speech API.",
      title = Some("Transcribe Speech")
    )(_ =>
      IO.pure(
        CallToolResult(
          List(ContentBlock.Text("""{"status":"ready","message":"Transcription UI opened. Speak into your microphone."}"""))
        )
      )
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Transcript Server", version = "1.0.0"),
      tools = Some(ToolSet.of(transcribe)),
      resources = Some(
        UiResources.of[IO](
          UiResource(
            ResourceUri,
            "ui://transcript/mcp-app.html",
            html,
            Some("Transcript UI"),
            UiResourceMeta(permissions = Some(UiPermissions.microphone ++ UiPermissions.clipboardWrite))
          )
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("transcript-server/view").map(apply)

object Main extends ExampleMain("transcript-server", _ => TranscriptServer.load)
