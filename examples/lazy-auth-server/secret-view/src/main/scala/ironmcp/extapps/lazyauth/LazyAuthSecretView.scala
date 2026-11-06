package ironmcp.extapps.lazyauth

import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{ContentBlock, Implementation}
import org.scalajs.dom

/** Upstream's Lazy Auth protected view: renders the result of the `get_secret` tool.
  *
  * This view is only reached after the host has completed the OAuth flow
  * (the tool call that produces it requires a Bearer token).
  */
object LazyAuthSecretView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Lazy Auth Demo (Secret)", version = "1.0.0")

  private lazy val outputEl: dom.HTMLElement = Dom.byId("output")

  private def showResult(text: String, isError: Boolean): Unit =
    outputEl.innerHTML = ""
    val div = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
    div.className = if isError then "error" else "result"
    div.textContent = text
    val _ = outputEl.appendChild(div)

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    SafeArea.follow(view, Dom.query[dom.HTMLElement](".main")).surround(
      view.events.evalMap {
        case HostEvent.ToolResult(result) =>
          IO {
            val text = result.content.collectFirst { case ContentBlock.Text(t, _, _) => t }.getOrElse("(no content)")
            showResult(text, result.isError.getOrElse(false))
          }
        case _ => IO.unit
      }.compile.drain
    )
