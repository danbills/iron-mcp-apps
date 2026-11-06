package ironmcp.extapps

import cats.effect.{ExitCode, IO, IOApp}
import ironmcp.server.ServerHandler

/** An example's `main`: `object Main extends ExampleMain("quickstart", _ => QuickstartServer.load)`.
  * `load` gets the command line, for examples that read flags from it.
  */
abstract class ExampleMain(name: String, load: List[String] => IO[ServerHandler[IO]]) extends IOApp:
  def run(args: List[String]): IO[ExitCode] = load(args).flatMap(ExampleServer.main(args, _, name))
