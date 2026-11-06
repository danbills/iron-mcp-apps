package ironmcp.extapps.wiki

import cats.effect.IO
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, ViewPage}
import ironmcp.protocol.*
import ironmcp.server.*
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.http4s.client.Client
import org.http4s.client.middleware.FollowRedirect
import org.http4s.ember.client.EmberClientBuilder
import org.jsoup.Jsoup
import org.typelevel.ci.CIString

import java.net.{URI, URLDecoder}
import scala.jdk.CollectionConverters.*

object WikiServer:

  val ResourceUri: UiUri = "ui://wiki-explorer/mcp-app.html"

  val ExcludedPrefixes: List[String] = List(
    "Wikipedia:",
    "Help:",
    "File:",
    "Special:",
    "Talk:",
    "Template:",
    "Category:",
    "Portal:",
    "Draft:",
    "Module:",
    "MediaWiki:",
    "User:",
    "Main_Page"
  )

  def extractTitleFromUrl(url: String): String =
    try
      val uri = new URI(url)
      val path = uri.getPath
      val rawTitle = path.stripPrefix("/wiki/")
      URLDecoder.decode(rawTitle, "UTF-8").replace('_', ' ')
    catch
      case _: Throwable => url

  def extractWikiLinks(pageUri: URI, html: String): List[PageInfo] =
    val doc = Jsoup.parse(html, pageUri.toString)
    val elements = doc.select("a[href^=/wiki/]").asScala.toList
    val hrefs = elements
      .map(_.attr("href"))
      .filter { href =>
        href != pageUri.getPath &&
        !href.contains("#") &&
        !ExcludedPrefixes.exists(href.contains)
      }
      .distinct

    val origin = s"${pageUri.getScheme}://${pageUri.getAuthority}"
    hrefs.map { href =>
      val fullUrl = s"$origin$href"
      PageInfo(url = fullUrl, title = extractTitleFromUrl(fullUrl))
    }

  def apply(html: String, client: Client[IO]): McpServer[IO] =
    val redirectClient = FollowRedirect(3)(client)

    val getFirstDegreeLinks = McpTool[GetFirstDegreeLinksArgs](
      "get-first-degree-links",
      "Returns all Wikipedia pages that the given page links to directly. The widget is interactive and exposes tools for exploring the graph (expanding nodes to see their links), searching for articles, and querying visible nodes.",
      title = Some("Get First-Degree Links")
    ) { args =>
      val targetUrl = args.url.map(_.toString).getOrElse("https://en.wikipedia.org/wiki/Model_Context_Protocol")
      val pageUriTry = scala.util.Try(new URI(targetUrl))
      val valid = pageUriTry.isSuccess && targetUrl.matches("""^https?://[a-z0-9_-]+\.wikipedia\.org/wiki/.*""")

      if !valid then
        val errResult = GetFirstDegreeLinksResult(
          page = PageInfo(targetUrl, extractTitleFromUrl(targetUrl)),
          links = Nil,
          error = Some("Not a valid Wikipedia URL")
        )
        IO.pure(CallToolResult.structured(errResult.asJson.noSpaces, errResult.asJson))
      else
        val uriOpt = Uri.fromString(targetUrl).toOption
        uriOpt match
          case None =>
            val errResult = GetFirstDegreeLinksResult(
              page = PageInfo(targetUrl, extractTitleFromUrl(targetUrl)),
              links = Nil,
              error = Some("Invalid URL format")
            )
            IO.pure(CallToolResult.structured(errResult.asJson.noSpaces, errResult.asJson))
          case Some(hUri) =>
            val req = Request[IO](
              method = Method.GET,
              uri = hUri,
              headers = Headers(
                Header.Raw(CIString("User-Agent"), "WikiExplorer/1.0 (https://github.com/modelcontextprotocol/ext-apps)")
              )
            )
            redirectClient.run(req).use { resp =>
              if resp.status.isSuccess then
                resp.as[String].map { body =>
                  val doc = Jsoup.parse(body, targetUrl)
                  val canonical = Option(doc.select("link[rel=canonical]").attr("href")).filter(_.nonEmpty).getOrElse(targetUrl)
                  val title = extractTitleFromUrl(canonical)
                  val parsedUri = new URI(canonical)
                  val links = extractWikiLinks(parsedUri, body)
                  val result = GetFirstDegreeLinksResult(
                    page = PageInfo(canonical, title),
                    links = links,
                    error = None
                  )
                  CallToolResult.structured(result.asJson.noSpaces, result.asJson)
                }
              else
                val errMsg = if resp.status.code == 404 then "Page not found" else s"Fetch failed: ${resp.status.code}"
                val errResult = GetFirstDegreeLinksResult(
                  page = PageInfo(targetUrl, extractTitleFromUrl(targetUrl)),
                  links = Nil,
                  error = Some(errMsg)
                )
                IO.pure(CallToolResult.structured(errResult.asJson.noSpaces, errResult.asJson))
            }.handleError { err =>
              val errResult = GetFirstDegreeLinksResult(
                page = PageInfo(targetUrl, extractTitleFromUrl(targetUrl)),
                links = Nil,
                error = Some(err.getMessage)
              )
              CallToolResult.structured(errResult.asJson.noSpaces, errResult.asJson)
            }
    }.withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Wiki Explorer", version = "1.0.0"),
      tools = Some(ToolSet.of(getFirstDegreeLinks)),
      resources = Some(
        UiResources.of[IO](
          UiResource(
            ResourceUri,
            "Wiki Explorer UI",
            html,
            Some("Interactive force-directed graph visualization of Wikipedia articles")
          )
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] =
    for
      html   <- ViewPage.load("wiki-explorer-server/view")
      client <- EmberClientBuilder.default[IO].build.allocated.map(_._1)
    yield apply(html, client)

object Main extends ExampleMain("wiki-explorer-server", _ => WikiServer.load)
