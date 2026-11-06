package ironmcp.extapps.map

import cats.effect.{IO, Ref}
import io.circe.JsonObject
import io.circe.syntax.*
import io.github.iltotore.iron.*
import ironmcp.apps.*
import ironmcp.protocol.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client

class MapSuite extends CatsEffectSuite:

  private val meta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities.none
  )

  private val cannedNominatimJson =
    """[
      |  {
      |    "place_id": 12345,
      |    "licence": "Data © OpenStreetMap contributors",
      |    "osm_type": "relation",
      |    "osm_id": 65606,
      |    "lat": "48.8588897",
      |    "lon": "2.3200410",
      |    "display_name": "Paris, Île-de-France, France",
      |    "boundingbox": ["48.8155755", "48.9021560", "2.2241220", "2.4697602"],
      |    "type": "administrative",
      |    "importance": 0.98
      |  }
      |]""".stripMargin

  private val cannedApp = HttpApp[IO] { req =>
    if req.uri.query.params.get("q").contains("Paris") then
      IO.pure(Response[IO](Status.Ok).withEntity(cannedNominatimJson))
    else if req.uri.query.params.get("q").contains("Nowhere") then
      IO.pure(Response[IO](Status.Ok).withEntity("[]"))
    else
      IO.pure(Response[IO](Status.InternalServerError))
  }

  private val testClient = Client.fromHttpApp[IO](cannedApp)

  test("show-map returns viewUUID and has UI metadata") {
    for
      ref <- Ref.of[IO, Long](0L)
      server = MapServer("<html></html>", testClient, ref)
      toolsResult <- server.tools.get.list(ListToolsParams(meta))
      showTool = toolsResult.tools.find(_.name.contains("show-map")).get
      uiMeta = showTool._meta.flatMap(_.get(McpApps.UiKey)).flatMap(_.as[UiToolMeta].toOption)
      _ = assertEquals(uiMeta.flatMap(_.resourceUri), Some(MapServer.ResourceUri: String))
      resRaw <- server.tools.get.call(CallToolParams(name = "show-map".refineUnsafe, _meta = meta))
      res = resRaw.asInstanceOf[CallToolResult]
      _ = assertEquals(res.isError, None)
      block = res.content.head.asInstanceOf[ContentBlock.Text]
      _ = assert(block.text.contains("Displaying globe at: W:-0.5000, S:51.3000, E:0.3000, N:51.7000"))
      _ = assert(block._meta.isDefined)
      _ = assert(block._meta.get.exists(_._1.contains("viewUUID")))
    yield ()
  }

  test("geocode formats Nominatim results") {
    val args = JsonObject("query" -> "Paris".asJson)
    for
      ref <- Ref.of[IO, Long](0L)
      server = MapServer("<html></html>", testClient, ref)
      resRaw <- server.tools.get.call(CallToolParams(name = "geocode".refineUnsafe, arguments = Some(args), _meta = meta))
      res = resRaw.asInstanceOf[CallToolResult]
      _ = assertEquals(res.isError, None)
      text = res.content.head.asInstanceOf[ContentBlock.Text].text
      _ = assert(text.contains("1. Paris, Île-de-France, France"))
      _ = assert(text.contains("Coordinates: 48.858890, 2.320041"))
      _ = assert(text.contains("Bounding box: W:2.2241, S:48.8156, E:2.4698, N:48.9022"))
    yield ()
  }

  test("geocode handles empty results") {
    val args = JsonObject("query" -> "Nowhere".asJson)
    for
      ref <- Ref.of[IO, Long](0L)
      server = MapServer("<html></html>", testClient, ref)
      resRaw <- server.tools.get.call(CallToolParams(name = "geocode".refineUnsafe, arguments = Some(args), _meta = meta))
      res = resRaw.asInstanceOf[CallToolResult]
      _ = assertEquals(res.isError, None)
      text = res.content.head.asInstanceOf[ContentBlock.Text].text
      _ = assertEquals(text, """No results found for "Nowhere"""")
    yield ()
  }
