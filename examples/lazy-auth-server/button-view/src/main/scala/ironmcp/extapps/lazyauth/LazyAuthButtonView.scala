package ironmcp.extapps.lazyauth

import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{ContentBlock, Implementation}
import org.scalajs.dom

/** Upstream's Lazy Auth public view: buttons that call the protected tools.
  *
  * The view renders without auth and shows who is signed in, from the
  * server's public `whoami` tool (it never sees a token). "Sign in" calls
  * `unlock_secret`, which needs `mcp:read` and does nothing else, so the host
  * runs the OAuth flow without the secret being fetched. "Auth me" calls the
  * protected `get_secret` directly: the host gets a 401, signs in, retries,
  * and the result renders here.
  */
object LazyAuthButtonView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Lazy Auth Demo", version = "1.0.0")
  override def capabilities: AppCapabilities =
    AppCapabilities(availableDisplayModes = Some(List(DisplayMode.`inline`, DisplayMode.fullscreen)))

  private lazy val sessionEl: dom.HTMLElement           = Dom.byId("session")
  private lazy val signinBtn: dom.HTMLButtonElement     = Dom.byId("signin-btn")
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

  private def renderSession(s: ViewSession): IO[Unit] = IO {
    sessionEl.textContent =
      if s.signedIn then s"Signed in as ${s.username.orElse(s.subject).getOrElse("someone")} (${s.scopes.mkString(", ")})${if s.keyBound then ", token bound to this client's key" else ""}."
      else s"Not signed in. Locked: ${s.locked.map(_.name).mkString(", ")}."
    signinBtn.style.display = if s.lock("get_secret").isEmpty then "none" else ""
  }

  private def refreshSession(view: McpAppView[IO]): IO[Unit] =
    ViewAuth.session(view).flatMap(renderSession).handleErrorWith(e => IO(sessionEl.textContent = s"Session unknown: ${e.getMessage}"))

  private def signIn(view: McpAppView[IO]): IO[Unit] =
    ViewAuth.session(view).flatMap { s =>
      s.lock("get_secret") match
        case None => renderSession(s)
        case Some(locked) =>
          ViewAuth.unlock(view, locked).flatMap {
            case Right(after) => renderSession(after)
            case Left(ViewAuth.Outcome.SignInRequired)         => IO(renderOutput("The host did not sign you in.", isError = true))
            case Left(ViewAuth.Outcome.MoreAccessRequired(ss)) => IO(renderOutput(s"Still missing: ${ss.mkString(", ")}.", isError = true))
            case Left(ViewAuth.Outcome.Forbidden(why))         => IO(renderOutput(why, isError = true))
            case Left(ViewAuth.Outcome.Failed(e))              => IO(renderOutput(e.getMessage, isError = true))
          }
    }

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
        } >> refreshSession(view)

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    IO {
      ui.onClick(signinBtn) {
        IO(signinBtn.disabled = true) >> signIn(view).guarantee(IO(signinBtn.disabled = false))
      }

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
    } >> refreshSession(view).start >>
      SafeArea.follow(view, Dom.query[dom.HTMLElement](".main")).surround(
        view.events.compile.drain
      )
