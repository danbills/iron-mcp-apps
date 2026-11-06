package ironmcp.extapps.view

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import org.scalajs.dom

/** The bridge from DOM callbacks to `IO`, and the View's teardown hook. */
final class Ui(val dispatcher: Dispatcher[IO], teardown: Ref[IO, IO[Unit]]):

  /** Runs `io` from a callback; a failure goes to the console, as an unhandled rejection would. */
  def run(io: IO[Unit]): Unit =
    dispatcher.unsafeRunAndForget(io.handleErrorWith(error => IO(dom.console.error(error.toString))))

  def on(target: dom.EventTarget, event: String)(handler: dom.Event => IO[Unit]): Unit =
    target.addEventListener(event, (e: dom.Event) => run(handler(e)))

  def onClick(target: dom.EventTarget)(io: => IO[Unit]): Unit = on(target, "click")(_ => io)

  /** Runs before the host's `ui/resource-teardown` is answered (ext-apps' `onteardown`). */
  def onTeardown(io: IO[Unit]): IO[Unit] = teardown.set(io)
