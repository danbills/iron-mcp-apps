package ironmcp.extapps

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import io.circe.Json
import ironmcp.server.ServerHandler
import org.http4s.server.Server

/** Every example server in one JVM, as upstream's `run-all.ts`: ports from
  * `BASE_PORT` (default 3201, clear of upstream's 3101+) in the order below,
  * which is upstream's (alphabetical by directory). Prints the `SERVERS` JSON
  * that basic-host takes.
  */
object RunAll extends IOApp.Simple:

  private def std(load: IO[ServerHandler[IO]]): Int => Resource[IO, Server] =
    port => Resource.eval(load).flatMap(ExampleServer.http(_, port))

  private val examples: List[(String, Int => Resource[IO, Server])] = List(
    "basic-server"                 -> std(basic.BasicServer.load),
    "budget-allocator-server"      -> std(budget.BudgetServer.load),
    "cohort-heatmap-server"        -> std(cohort.CohortServer.load),
    "customer-segmentation-server" -> std(segmentation.SegmentationServer.load),
    "debug-server"                 -> std(debug.DebugServer.load(Nil)),
    "integration-server"           -> std(integration.IntegrationServer.load),
    "lazy-auth-server"             -> (port => lazyauth.LazyAuthServer.loadRoutes(s"http://localhost:$port/mcp").flatMap(ExampleServer.httpRoutes(_, port))),
    "map-server"                   -> std(map.MapServer.load),
    "pdf-server"                   -> std(pdf.PdfServer.load),
    "qr-server"                    -> std(qr.QrServer.load),
    "scenario-modeler-server"      -> std(scenario.ScenarioServer.load),
    "quickstart"                   -> std(quickstart.QuickstartServer.load),
    "sheet-music-server"           -> std(sheetmusic.SheetMusicServer.load),
    "system-monitor-server"        -> std(monitor.MonitorServer.load),
    "transcript-server"            -> std(transcript.TranscriptServer.load),
    "video-resource-server"        -> std(video.VideoServer.load),
    "say-server"                   -> std(say.SayServer.load),
    "shadertoy-server"             -> std(shadertoy.ShadertoyServer.load),
    "threejs-server"               -> std(three.ThreejsServer.load),
    "wiki-explorer-server"         -> std(wiki.WikiServer.load)
  )

  def run: IO[Unit] =
    val base = sys.env.get("BASE_PORT").flatMap(_.toIntOption).getOrElse(3201)
    val only = sys.env.get("EXAMPLE").filter(_.nonEmpty)
    val chosen = examples.filter((name, _) => only.forall(_ == name)).zipWithIndex
    chosen
      .traverse { case ((name, serve), i) =>
        val port = base + i
        serve(port).as(name -> s"http://localhost:$port/mcp")
      }
      .use { urls =>
        urls.traverse_((name, url) => IO.println(s"$name: $url")) >>
          IO.println(s"SERVERS=${Json.arr(urls.map((_, url) => Json.fromString(url))*).noSpaces}") >>
          IO.never
      }
