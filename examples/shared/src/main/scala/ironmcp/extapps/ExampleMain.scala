package ironmcp.extapps

import cats.effect.{ExitCode, IO, IOApp}
import ironmcp.server.ServerHandler

/** An example's `main`: `object Main extends ExampleMain("quickstart", QuickstartServer.load)`. */
abstract class ExampleMain(name: String, load: => IO[ServerHandler[IO]]) extends IOApp:
  def run(args: List[String]): IO[ExitCode] = load.flatMap(ExampleServer.main(args, _, name))
