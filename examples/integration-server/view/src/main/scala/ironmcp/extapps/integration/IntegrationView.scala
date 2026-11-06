package ironmcp.extapps.integration

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, Printer}
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Log, Ui}
import ironmcp.protocol.*
import org.scalajs.dom

import scala.concurrent.duration.*

/** Upstream's integration View (React there): the basic View's four actions,
  * plus downloads when the host offers them. ext-apps' e2e suite clicks Send
  * Message, Send Log and Open Link and checks the host saw each.
  */
object IntegrationView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Get Time App", version = "1.0.0")

  // Upstream's React View keeps the host context for its safe-area padding only.
  override def theming: Boolean = false

  private val log = Log("[APP]")

  /** `JSON.stringify(value, null, 2)`. */
  private val pretty = Printer.spaces2.copy(colonLeft = "")

  private def page(canDownload: Boolean): String =
    val download =
      if !canDownload then ""
      else
        """
        |      <div class="action">
        |        <p>Download file via EmbeddedResource or ResourceLink</p>
        |        <div style="display: flex; gap: 8px;">
        |          <button id="download-embedded">Embedded</button>
        |          <button id="download-link">Link</button>
        |        </div>
        |      </div>""".stripMargin
    s"""<main class="main">
       |      <p class="notice">Watch activity in the DevTools console!</p>
       |      <div class="action">
       |        <p><strong>Server Time:</strong> <code class="serverTime">Loading...</code></p>
       |        <button id="get-time">Get Server Time</button>
       |      </div>
       |      <div class="action">
       |        <textarea id="message-text">This is message text.</textarea>
       |        <button id="send-message">Send Message</button>
       |      </div>
       |      <div class="action">
       |        <input type="text" id="log-text" value="This is log text.">
       |        <button id="send-log">Send Log</button>
       |      </div>
       |      <div class="action">
       |        <input type="url" id="link-url" value="https://modelcontextprotocol.io/">
       |        <button id="open-link">Open Link</button>
       |      </div>$download
       |    </main>""".stripMargin

  private def extractTime(result: CallToolResult): String =
    result.structuredContent.flatMap(_.hcursor.get[String]("time").toOption).getOrElse("[ERROR]")

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    val canDownload = view.hostCapabilities.downloadFile.isDefined
    for
      _ <- IO(Dom.byId[dom.HTMLElement]("root").innerHTML = page(canDownload))
      main       = Dom.query[dom.HTMLElement](".main")
      serverTime = Dom.query[dom.HTMLElement](".serverTime")
      _ <- ui.onTeardown(log.info("App is being torn down") >> IO.sleep(500.millis) >> log.info("App teardown complete"))
      _ <- IO(wire(view, ui, serverTime, canDownload))
      _ <- SafeArea.follow(view, main).surround(
             view.events
               .evalMap {
                 case HostEvent.ToolInput(arguments) => log.info("Received tool call input:", Json.fromJsonObject(arguments).noSpaces)
                 case HostEvent.ToolResult(result)   => log.info("Received tool call result:", result) >> IO(serverTime.textContent = extractTime(result))
                 case HostEvent.Unreadable(method, _, why) => log.error(s"$method: $why")
                 case _                              => IO.unit
               }
               .compile
               .drain
           )
    yield ()

  private def wire(view: McpAppView[IO], ui: Ui, serverTime: dom.HTMLElement, canDownload: Boolean): Unit =
    val messageText = Dom.byId[dom.HTMLTextAreaElement]("message-text")
    val logText     = Dom.byId[dom.HTMLInputElement]("log-text")
    val linkUrl     = Dom.byId[dom.HTMLInputElement]("link-url")

    ui.onClick(Dom.byId("get-time")) {
      log.info("Calling get-time tool...") >>
        view.callServerTool("get-time").attempt.flatMap {
          case Right(result) => log.info("get-time result:", result) >> IO(serverTime.textContent = extractTime(result))
          case Left(error)   => log.error(error) >> IO(serverTime.textContent = "[ERROR]")
        }
    }
    ui.onClick(Dom.byId("send-message")) {
      log.info("Sending message text to Host:", messageText.value) >>
        view.sendMessage(List(ContentBlock.Text(messageText.value))).timeout(5.seconds).attempt.flatMap {
          case Right(accepted) => log.info("Message", if accepted then "accepted" else "rejected")
          case Left(error)     => log.error("Message send error:", error)
        }
    }
    ui.onClick(Dom.byId("send-log")) {
      log.info("Sending log text to Host:", logText.value) >> view.log(LoggingLevel.info, Json.fromString(logText.value))
    }
    ui.onClick(Dom.byId("open-link")) {
      log.info("Sending open link request to Host:", linkUrl.value) >>
        view.openLink(linkUrl.value).flatMap(accepted => log.info("Open link request", if accepted then "accepted" else "rejected"))
    }
    if canDownload then
      ui.onClick(Dom.byId("download-embedded")) {
        IO.realTimeDate.flatMap { now =>
          val sample = pretty.print(Json.obj("time" -> serverTime.textContent.asJson, "exported" -> now.toISOString().asJson))
          log.info("Requesting file download...") >>
            view
              .downloadFile(List(ContentBlock.Embedded(ResourceContents.Text("file:///export.json", sample, Some("application/json")))))
              .flatMap(accepted => log.info("Download", if accepted then "accepted" else "rejected"))
        }
      }
      ui.onClick(Dom.byId("download-link")) {
        log.info("Requesting resource link download...") >>
          view
            .downloadFile(List(ContentBlock.Link(Resource(uri = "resource:///sample-report.txt", name = "sample-report.txt", mimeType = Some("text/plain")))))
            .flatMap(accepted => log.info("Resource link download", if accepted then "accepted" else "rejected"))
      }
