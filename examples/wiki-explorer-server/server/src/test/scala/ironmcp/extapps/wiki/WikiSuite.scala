package ironmcp.extapps.wiki

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import io.github.iltotore.iron.*
import ironmcp.protocol.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client

class WikiSuite extends CatsEffectSuite:

  private val cannedHtml =
    """<!DOCTYPE html>
      |<html>
      |<head>
      |  <link rel="canonical" href="https://en.wikipedia.org/wiki/Model_Context_Protocol" />
      |</head>
      |<body>
      |  <h1>Model Context Protocol</h1>
      |  <p>The <a href="/wiki/Anthropic">Anthropic</a> company created <a href="/wiki/Claude_(language_model)">Claude</a>.</p>
      |  <p>See also <a href="/wiki/Help:Contents">Help</a> and <a href="/wiki/Model_Context_Protocol">Self</a>.</p>
      |  <p>External <a href="https://example.com">Example</a> and anchor <a href="/wiki/Target#Section">Anchor</a>.</p>
      |</body>
      |</html>""".stripMargin

  private val cannedApp = HttpApp[IO] { req =>
    if req.uri.path.renderString == "/wiki/Model_Context_Protocol" then
      IO.pure(Response[IO](Status.Ok).withEntity(cannedHtml))
    else if req.uri.path.renderString == "/wiki/Missing" then
      IO.pure(Response[IO](Status.NotFound))
    else
      IO.pure(Response[IO](Status.NotFound))
  }

  private val meta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities.none
  )

  private val testClient = Client.fromHttpApp[IO](cannedApp)
  private val server     = WikiServer("<html></html>", testClient)

  test("get-first-degree-links extracts links from canned HTML") {
    val args = JsonObject("url" -> "https://en.wikipedia.org/wiki/Model_Context_Protocol".asJson)
    for
      resRaw <- server.tools.get.call(CallToolParams(name = "get-first-degree-links".refineUnsafe, arguments = Some(args), _meta = meta))
      res = resRaw.asInstanceOf[CallToolResult]
      _   = assertEquals(res.isError, Some(false))
      parsed = res.structuredContent.flatMap(_.as[GetFirstDegreeLinksResult].toOption)
      _   = assert(parsed.isDefined)
      r   = parsed.get
      _   = assertEquals(r.page.title, "Model Context Protocol")
      _   = assertEquals(r.error, None)
      _   = assertEquals(r.links.map(_.title).toSet, Set("Anthropic", "Claude (language model)"))
    yield ()
  }

  test("get-first-degree-links rejects invalid URL") {
    val args = JsonObject("url" -> "https://malicious.com/not-wiki".asJson)
    for
      resRaw <- server.tools.get.call(CallToolParams(name = "get-first-degree-links".refineUnsafe, arguments = Some(args), _meta = meta))
      res = resRaw.asInstanceOf[CallToolResult]
      _   = assertEquals(res.isError, Some(false))
      parsed = res.structuredContent.flatMap(_.as[GetFirstDegreeLinksResult].toOption)
      _   = assert(parsed.isDefined)
      r   = parsed.get
      _   = assertEquals(r.error, Some("Not a valid Wikipedia URL"))
      _   = assertEquals(r.links, Nil)
    yield ()
  }

  test("get-first-degree-links handles 404") {
    val args = JsonObject("url" -> "https://en.wikipedia.org/wiki/Missing".asJson)
    for
      resRaw <- server.tools.get.call(CallToolParams(name = "get-first-degree-links".refineUnsafe, arguments = Some(args), _meta = meta))
      res = resRaw.asInstanceOf[CallToolResult]
      _   = assertEquals(res.isError, Some(false))
      parsed = res.structuredContent.flatMap(_.as[GetFirstDegreeLinksResult].toOption)
      _   = assert(parsed.isDefined)
      r   = parsed.get
      _   = assertEquals(r.error, Some("Page not found"))
      _   = assertEquals(r.links, Nil)
    yield ()
  }
