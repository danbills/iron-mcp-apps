package ironmcp.extapps.map

import cats.effect.{IO, Ref}
import io.circe.{Decoder, Json}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, ViewPage}
import ironmcp.protocol.*
import ironmcp.server.*
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString

import java.util.UUID
import scala.concurrent.duration.*

final case class NominatimItem(
    place_id: Long,
    lat: String,
    lon: String,
    display_name: String,
    boundingbox: List[String],
    `type`: String,
    importance: Double
) derives Decoder

object MapServer:

  val ResourceUri: UiUri = "ui://cesium-map/mcp-app.html"

  private val RateLimitMs = 1100L

  def apply(html: String, client: Client[IO], lastRequestRef: Ref[IO, Long]): McpServer[IO] =
    val cspMeta = UiResourceMeta(
      csp = Some(UiCsp(
        connectDomains = Some(List("https://*.openstreetmap.org", "https://cesium.com", "https://*.cesium.com")),
        resourceDomains = Some(List("https://*.openstreetmap.org", "https://cesium.com", "https://*.cesium.com"))
      ))
    )

    val showMap = McpTool[ShowMapArgs](
      "show-map",
      "Display an interactive world map zoomed to a specific bounding box. Use the GeoCode tool to find the bounding box of a location. The widget is interactive and exposes tools for navigation (fly to locations) and querying the current view.",
      title = Some("Show Map")
    ) { args =>
      val west = args.west.map(_.toDouble).getOrElse(-0.5)
      val south = args.south.map(_.toDouble).getOrElse(51.3)
      val east = args.east.map(_.toDouble).getOrElse(0.3)
      val north = args.north.map(_.toDouble).getOrElse(51.7)
      val label = args.label.map(_.toString)

      val text = f"Displaying globe at: W:$west%.4f, S:$south%.4f, E:$east%.4f, N:$north%.4f${label.map(l => s" ($l)").getOrElse("")}"
      val uuid = UUID.randomUUID().toString
      val meta: Map[MetaKey, Json] = Map("viewUUID".assume[MetaKeyC] -> Json.fromString(uuid))
      IO.pure(CallToolResult(List(ContentBlock.Text(text, _meta = Some(meta)))))
    }.withUi(ResourceUri)

    val geocode = McpTool[GeocodeArgs](
      "geocode",
      "Search for places using OpenStreetMap. Returns coordinates and bounding boxes for up to 5 matches.",
      title = Some("Geocode")
    ) { args =>
      val query: String = args.query
      val nowIo = IO.realTime.map(_.toMillis)

      nowIo.flatMap { now =>
        lastRequestRef.get.flatMap { last =>
          val elapsed = now - last
          val waitTime = if elapsed < RateLimitMs then RateLimitMs - elapsed else 0L
          IO.sleep(waitTime.millis) >> nowIo.flatMap(lastRequestRef.set)
        }
      } >> {
        val uriStr = s"https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&limit=5"
        val hUri = Uri.unsafeFromString(uriStr)
        val req = Request[IO](
          method = Method.GET,
          uri = hUri,
          headers = Headers(
            Header.Raw(CIString("User-Agent"), "MCP-CesiumMap-Example/1.0 (https://github.com/modelcontextprotocol)")
          )
        )

        client.run(req).use { resp =>
          if resp.status.isSuccess then
            resp.as[String].map { body =>
              io.circe.parser.decode[List[NominatimItem]](body) match
                case Left(err) =>
                  CallToolResult(List(ContentBlock.Text(s"Geocoding error: ${err.getMessage}")), isError = Some(true))
                case Right(Nil) =>
                  CallToolResult(List(ContentBlock.Text(s"""No results found for "$query"""")))
                case Right(results) =>
                  val text = results.zipWithIndex.map { case (r, i) =>
                    val lat = r.lat.toDoubleOption.getOrElse(0.0)
                    val lon = r.lon.toDoubleOption.getOrElse(0.0)
                    val south = r.boundingbox.lift(0).flatMap(_.toDoubleOption).getOrElse(0.0)
                    val north = r.boundingbox.lift(1).flatMap(_.toDoubleOption).getOrElse(0.0)
                    val west = r.boundingbox.lift(2).flatMap(_.toDoubleOption).getOrElse(0.0)
                    val east = r.boundingbox.lift(3).flatMap(_.toDoubleOption).getOrElse(0.0)
                    f"${i + 1}. ${r.display_name}\n   Coordinates: $lat%.6f, $lon%.6f\n   Bounding box: W:$west%.4f, S:$south%.4f, E:$east%.4f, N:$north%.4f"
                  }.mkString("\n\n")
                  CallToolResult(List(ContentBlock.Text(text)))
            }
          else
            IO.pure(CallToolResult(List(ContentBlock.Text(s"Nominatim API error: ${resp.status.code}")), isError = Some(true)))
        }.handleError { err =>
          CallToolResult(List(ContentBlock.Text(s"Geocoding error: ${err.getMessage}")), isError = Some(true))
        }
      }
    }

    McpServer(
      info = Implementation(name = "CesiumJS Map Server", version = "1.0.0"),
      tools = Some(ToolSet.of(showMap, geocode)),
      resources = Some(
        UiResources.of[IO](
          UiResource(
            ResourceUri,
            "CesiumJS Map View UI",
            html,
            Some("Interactive 3D globe with CesiumJS"),
            cspMeta
          )
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] =
    for
      html   <- ViewPage.load("map-server/view")
      client <- EmberClientBuilder.default[IO].build.allocated.map(_._1)
      ref    <- Ref.of[IO, Long](0L)
    yield apply(html, client, ref)

object Main extends ExampleMain("map-server", _ => MapServer.load)
