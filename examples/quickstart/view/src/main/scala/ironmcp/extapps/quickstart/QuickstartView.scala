package ironmcp.extapps.quickstart

import cats.effect.std.Dispatcher
import cats.effect.{IO, IOApp}
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.protocol.{CallToolResult, ContentBlock, Implementation}
import org.scalajs.dom

/** Upstream's quickstart View: shows `get-time`'s result, and asks for a fresh
  * one through the host when the button is clicked.
  */
object QuickstartView extends IOApp.Simple:

  private val appInfo = Implementation(name = "Get Time App", version = "1.0.0")

  def run: IO[Unit] =
    val connected =
      for
        view       <- McpAppView.inWindow[IO](appInfo)
        _          <- AutoResize(view)
        dispatcher <- Dispatcher.sequential[IO]
      yield (view, dispatcher)

    connected.use { (view, dispatcher) =>
      val serverTime = dom.document.getElementById("server-time")
      val button     = dom.document.getElementById("get-time-btn")

      def show(result: CallToolResult): IO[Unit] =
        IO(serverTime.textContent = result.content.collectFirst { case ContentBlock.Text(text, _, _) => text }.getOrElse("[ERROR]"))

      IO(button.addEventListener("click", (_: dom.Event) => dispatcher.unsafeRunAndForget(view.callServerTool("get-time").flatMap(show)))) >>
        view.events
          .evalMap {
            case HostEvent.ToolResult(result) => show(result)
            case _                            => IO.unit
          }
          .compile
          .drain
    }
