package ironmcp.extapps.view

import cats.effect.std.Dispatcher
import cats.effect.{IO, IOApp, Ref, Resource}
import ironmcp.apps.view.*
import ironmcp.protocol.Implementation

/** What every example View does, as ext-apps' `App` does with its defaults:
  * connect to the host, follow its theme (when the upstream View does), keep
  * it told of the page's size, then run [[start]] until the host tears it down.
  */
abstract class ExampleView extends IOApp.Simple:

  def appInfo: Implementation
  def capabilities: AppCapabilities = AppCapabilities()

  /** `applyDocumentTheme` / `applyHostStyleVariables` / `applyHostFonts`, as the upstream View calls them. */
  def theming: Boolean = true

  /** ext-apps' `autoResize` (on by default). */
  def autoResize: Boolean = true

  /** Tools the View offers the host, if any. */
  def tools: IO[Option[ViewToolProvider[IO]]] = IO.pure(None)

  /** The View's own work: render, wire the page, and consume `view.events`. */
  def start(view: McpAppView[IO], ui: Ui): IO[Unit]

  final def run: IO[Unit] =
    val running =
      for
        teardown   <- Resource.eval(Ref.of[IO, IO[Unit]](IO.unit))
        provider   <- Resource.eval(tools)
        view       <- McpAppView.inWindow[IO](appInfo, capabilities, onTeardown = teardown.get.flatten, tools = provider)
        _          <- if theming then HostTheming.follow(view) else Resource.unit[IO]
        _          <- if autoResize then AutoResize(view) else Resource.unit[IO]
        dispatcher <- Dispatcher.parallel[IO](await = false)
      yield (view, Ui(dispatcher, teardown))
    running.use((view, ui) => start(view, ui) >> IO.never)
