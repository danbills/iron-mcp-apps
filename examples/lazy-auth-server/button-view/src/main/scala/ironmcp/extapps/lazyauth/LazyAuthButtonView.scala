package ironmcp.extapps.lazyauth

import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{ContentBlock, Implementation}
import org.scalajs.dom

/** Upstream's Lazy Auth public view: buttons that call the protected tools.
  *
  * The view itself renders without auth. Clicking "Auth me" calls the protected
  * `get_secret` tool via `callServerTool`; the host receives a 401 from the
  * server, runs the OAuth flow, retries the call, and the result renders here.
  */
object LazyAuthButtonView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Lazy Auth Demo", version = "1.0.0")
  override def capabilities: AppCapabilities =
    AppCapabilities(availableDisplayModes = Some(List(DisplayMode.`inline`, DisplayMode.fullscreen)))

  private lazy val authBtn: dom.HTMLButtonElement       = Dom.byId("auth-btn")
  private lazy val revokeBtn: dom.HTMLButtonElement     = Dom.byId("revoke-btn")
  private lazy val fullscreenBtn: dom.HTMLButtonElement = Dom.byId("fullscreen-btn")
  private lazy val outputEl: dom.HTMLElement            = Dom.byId("output")

  private var currentDisplayMode: DisplayMode = DisplayMode.`inline`

  private def renderOutput(text: String, isError: Boolean): Unit =
    outputEl.innerHTML = ""
    val div = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
    div.className = if isError then "error" else "result"
    div.textContent = text
    val _ = outputEl.appendChild(div)

  private def callTool(view: McpAppView[IO], name: String): IO[Unit] =
    IO(outputEl.innerHTML = "") >>
      IO(dom.console.info(s"Calling $name tool...")) >>
      view
        .callServerTool(name)
        .flatMap { res =>
          IO {
            val text = res.content.collectFirst { case ContentBlock.Text(t, _, _) => t }.getOrElse("(no content)")
            renderOutput(text, res.isError.getOrElse(false))
          }
        }
        .handleErrorWith { e =>
          IO(renderOutput(e.getMessage, isError = true))
        }

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    IO {
      ui.onClick(authBtn) {
        IO(authBtn.disabled = true) >>
          callTool(view, "get_secret").guarantee(IO(authBtn.disabled = false))
      }

      ui.onClick(revokeBtn) {
        IO(revokeBtn.disabled = true) >>
          callTool(view, "revoke_auth_token").guarantee(IO(revokeBtn.disabled = false))
      }

      ui.onClick(fullscreenBtn) {
        IO(fullscreenBtn.disabled = true) >>
          (for
            next <- IO(if currentDisplayMode == DisplayMode.fullscreen then DisplayMode.`inline` else DisplayMode.fullscreen)
            mode <- view.requestDisplayMode(next)
            _    <- IO { currentDisplayMode = mode }
          yield ()).handleErrorWith(e => IO(dom.console.warn("Display mode not supported:", e.toString)))
            .guarantee(IO(fullscreenBtn.disabled = false))
      }
    } >>
      SafeArea.follow(view, Dom.query[dom.HTMLElement](".main")).surround(
        view.events.compile.drain
      )
