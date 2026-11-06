package ironmcp.extapps

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import io.circe.Json
import ironmcp.server.ServerHandler

/** Every example server in one JVM, as upstream's `run-all.ts`: ports from
  * `BASE_PORT` (default 3201, clear of upstream's 3101+) in the order below,
  * which is upstream's (alphabetical by directory). Prints the `SERVERS` JSON
  * that basic-host takes.
  */
object RunAll extends IOApp.Simple:

  private val examples: List[(String, IO[ServerHandler[IO]])] = List(
    "basic-server"       -> basic.BasicServer.load,
    "budget-allocator-server" -> budget.BudgetServer.load,
    "cohort-heatmap-server"   -> cohort.CohortServer.load,
    "customer-segmentation-server" -> segmentation.SegmentationServer.load,
    "debug-server"       -> debug.DebugServer.load(Nil),
    "integration-server" -> integration.IntegrationServer.load,
    "qr-server"          -> qr.QrServer.load,
    "scenario-modeler-server" -> scenario.ScenarioServer.load,
    "quickstart"   -> quickstart.QuickstartServer.load,
    "video-resource-server" -> video.VideoServer.load
  )

  def run: IO[Unit] =
    val base = sys.env.get("BASE_PORT").flatMap(_.toIntOption).getOrElse(3201)
    val only = sys.env.get("EXAMPLE").filter(_.nonEmpty)
    val chosen = examples.filter((name, _) => only.forall(_ == name)).zipWithIndex
    chosen
      .traverse { case ((name, load), i) =>
        val port = base + i
        Resource.eval(load).flatMap(ExampleServer.http(_, port)).as(name -> s"http://localhost:$port/mcp")
      }
      .use { urls =>
        urls.traverse_((name, url) => IO.println(s"$name: $url")) >>
          IO.println(s"SERVERS=${Json.arr(urls.map((_, url) => Json.fromString(url))*).noSpaces}") >>
          IO.never
      }
