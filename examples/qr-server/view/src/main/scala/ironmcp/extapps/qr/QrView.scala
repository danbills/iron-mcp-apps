package ironmcp.extapps.qr

import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{ContentBlock, Implementation}
import org.scalajs.dom

/** Upstream's QR View (inline in its Python server): shows the image block. */
object QrView extends ExampleView:

  val appInfo: Implementation   = Implementation(name = "QR View", version = "1.0.0")
  override def theming: Boolean = false

  private val Allowed = Set("image/png", "image/jpeg", "image/gif")

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    SafeArea.follow(view).surround(
      view.events
        .evalMap {
          case HostEvent.ToolResult(result) =>
            result.content.collectFirst { case ContentBlock.Image(data, mime, _, _) => (data: String, mime: String) } match
              case None => IO.unit
              case Some((data, mime)) =>
                IO {
                  val qr = Dom.byId[dom.HTMLElement]("qr")
                  qr.innerHTML = ""
                  val image = dom.document.createElement("img").asInstanceOf[dom.HTMLImageElement]
                  image.src = s"data:${if Allowed(mime) then mime else "image/png"};base64,$data"
                  image.alt = "QR Code"
                  qr.appendChild(image)
                }
          case _ => IO.unit
        }
        .compile
        .drain
    )
