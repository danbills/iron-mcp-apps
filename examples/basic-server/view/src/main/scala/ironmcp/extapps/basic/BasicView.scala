package ironmcp.extapps.basic

import cats.effect.IO
import io.circe.Json
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{CallToolResult, ContentBlock, Implementation, LoggingLevel}
import org.scalajs.dom

import scala.concurrent.duration.*

/** Upstream's vanilla JS basic View: the server time, and a button each for
  * calling the server, sending a message, logging and opening a link.
  */
object BasicView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Get Time App", version = "1.0.0")

  private def extractTime(result: CallToolResult): String =
    result.structuredContent.flatMap(_.hcursor.get[String]("time").toOption).getOrElse("[ERROR]")

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    val main        = Dom.query[dom.HTMLElement](".main")
    val serverTime  = Dom.byId[dom.HTMLElement]("server-time")
    val messageText = Dom.byId[dom.HTMLTextAreaElement]("message-text")
    val logText     = Dom.byId[dom.HTMLInputElement]("log-text")
    val linkUrl     = Dom.byId[dom.HTMLInputElement]("link-url")

    def info(parts: Any*): IO[Unit] = IO(dom.console.info(parts.map(_.toString).mkString(" ")))

    val wire = IO {
      ui.onClick(Dom.byId("get-time-btn")) {
        info("Calling get-time tool...") >>
          view.callServerTool("get-time").attempt.flatMap {
            case Right(result) => info("get-time result:", result) >> IO(serverTime.textContent = extractTime(result))
            case Left(error)   => IO(dom.console.error(error.toString)) >> IO(serverTime.textContent = "[ERROR]")
          }
      }
      ui.onClick(Dom.byId("send-message-btn")) {
        info("Sending message text to Host:", messageText.value) >>
          view.sendMessage(List(ContentBlock.Text(messageText.value))).timeout(5.seconds).attempt.flatMap {
            case Right(accepted) => info("Message", if accepted then "accepted" else "rejected")
            case Left(error)     => IO(dom.console.error(s"Message send error: $error"))
          }
      }
      ui.onClick(Dom.byId("send-log-btn")) {
        info("Sending log text to Host:", logText.value) >> view.log(LoggingLevel.info, Json.fromString(logText.value))
      }
      ui.onClick(Dom.byId("open-link-btn")) {
        info("Sending open link request to Host:", linkUrl.value) >>
          view.openLink(linkUrl.value).flatMap(accepted => info("Open link request", if accepted then "accepted" else "rejected"))
      }
    }

    ui.onTeardown(info("App is being torn down")) >> wire >>
      SafeArea.follow(view, main).surround(
        view.events
          .evalMap {
            case HostEvent.ToolInput(arguments)   => info("Received tool call input:", Json.fromJsonObject(arguments).noSpaces)
            case HostEvent.ToolResult(result)     => info("Received tool call result:", result) >> IO(serverTime.textContent = extractTime(result))
            case HostEvent.ToolCancelled(reason)  => info("Tool call cancelled:", reason.getOrElse(""))
            case HostEvent.Unreadable(method, _, why) => IO(dom.console.error(s"$method: $why"))
            case HostEvent.ToolInputPartial(_)    => IO.unit
          }
          .compile
          .drain
      )
