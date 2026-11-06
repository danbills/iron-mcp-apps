package ironmcp.extapps.wiki

import cats.effect.IO
import forcegraph.{D3Force3D, ForceGraph}
import io.circe.{Codec, Decoder, Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{CallToolResult, ContentBlock, Implementation}
import ironmcp.schema.JsonSchemaOf
import org.scalajs.dom
import org.scalajs.dom.HTMLElement

import scala.collection.mutable
import scala.scalajs.js

final case class NoArgs() derives Decoder, JsonSchemaOf

final case class SearchArticleArgs(query: String) derives Codec.AsObject, JsonSchemaOf
final case class IdentifierArgs(identifier: String) derives Codec.AsObject, JsonSchemaOf

final case class SearchArticleResult(
    success: Boolean,
    article: Option[PageInfo] = None,
    linksFound: Int = 0,
    error: Option[String] = None
) derives Codec.AsObject

final case class CurrentArticleInfo(
    url: String,
    title: String,
    state: String,
    isExpanded: Boolean,
    hasError: Boolean,
    errorMessage: Option[String] = None
) derives Codec.AsObject

final case class CurrentArticleResult(
    hasSelection: Boolean,
    article: Option[CurrentArticleInfo]
) derives Codec.AsObject

final case class HighlightNodeResult(
    success: Boolean,
    node: Option[PageInfo] = None,
    error: Option[String] = None,
    availableNodes: Option[List[String]] = None
) derives Codec.AsObject

final case class ExpandNodeResult(
    success: Boolean,
    alreadyExpanded: Option[Boolean] = None,
    node: Option[PageInfo] = None,
    linksAdded: Int = 0,
    error: Option[String] = None,
    availableNodes: Option[List[String]] = None
) derives Codec.AsObject

final case class VisibleNodeInfo(
    url: String,
    title: String,
    state: String,
    isExpanded: Boolean,
    hasError: Boolean
) derives Codec.AsObject

final case class VisibleNodesResult(
    totalNodes: Int,
    expandedNodes: Int,
    errorNodes: Int,
    nodes: List[VisibleNodeInfo]
) derives Codec.AsObject

object WikiView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Wiki Explorer", version = "1.0.0")

  private val nodes: mutable.Buffer[js.Dynamic] = mutable.Buffer.empty
  private val links: mutable.Buffer[js.Dynamic] = mutable.Buffer.empty

  private var selectedNodeUrl: Option[String] = None
  private var initialUrl: Option[String]      = None

  private var graphOpt: Option[ForceGraph]           = None
  private var currentView: Option[McpAppView[IO]]    = None

  private lazy val container: HTMLElement    = Dom.byId("graph")
  private lazy val popup: HTMLElement        = Dom.byId("popup")
  private lazy val popupTitle: HTMLElement    = popup.querySelector(".popup-title").asInstanceOf[HTMLElement]
  private lazy val popupError: HTMLElement    = popup.querySelector(".popup-error").asInstanceOf[HTMLElement]
  private lazy val openBtn: HTMLElement       = Dom.byId("open-btn")
  private lazy val expandBtn: HTMLElement     = Dom.byId("expand-btn")
  private lazy val resetBtn: HTMLElement      = Dom.byId("reset-graph")
  private lazy val zoomInBtn: HTMLElement     = Dom.byId("zoom-in")
  private lazy val zoomOutBtn: HTMLElement    = Dom.byId("zoom-out")

  private def getCssColor(varName: String): String =
    val style = dom.window.getComputedStyle(dom.document.documentElement)
    val color = style.getPropertyValue(varName).trim
    if color.nonEmpty then color else "#000"

  private def addNode(
      url: String,
      title: String,
      state: String = "default",
      initialPos: Option[(Double, Double)] = None
  ): Boolean =
    val existing = nodes.find(n => n.selectDynamic("url").asInstanceOf[String] == url)
    if existing.isDefined then false
    else
      val node = js.Dynamic.literal(
        "url"   -> url,
        "title" -> title,
        "state" -> state
      )
      initialPos.foreach { (x, y) =>
        node.updateDynamic("x")(x + (scala.util.Random.nextDouble() - 0.5) * 20.0)
        node.updateDynamic("y")(y + (scala.util.Random.nextDouble() - 0.5) * 20.0)
      }
      nodes += node
      true

  private def updateNodeTitle(url: String, title: String): Unit =
    nodes.find(n => n.selectDynamic("url").asInstanceOf[String] == url).foreach { n =>
      n.updateDynamic("title")(title)
    }

  private def setNodeState(url: String, state: String, errorMessage: Option[String] = None): Unit =
    nodes.find(n => n.selectDynamic("url").asInstanceOf[String] == url).foreach { n =>
      n.updateDynamic("state")(state)
      errorMessage match
        case Some(msg) => n.updateDynamic("errorMessage")(msg)
        case None      => n.updateDynamic("errorMessage")(js.undefined)
    }

  private def addEdge(sourceUrl: String, targetUrl: String): Boolean =
    val existing = links.exists { l =>
      val src = l.selectDynamic("source")
      val tgt = l.selectDynamic("target")
      val s = if js.typeOf(src) == "string" then src.asInstanceOf[String] else src.selectDynamic("url").asInstanceOf[String]
      val t = if js.typeOf(tgt) == "string" then tgt.asInstanceOf[String] else tgt.selectDynamic("url").asInstanceOf[String]
      s == sourceUrl && t == targetUrl
    }
    if existing then false
    else
      links += js.Dynamic.literal("source" -> sourceUrl, "target" -> targetUrl)
      true

  private def updateGraph(): Unit =
    graphOpt.foreach { g =>
      val data = js.Dynamic.literal(
        "nodes" -> js.Array(nodes.toSeq*),
        "links" -> js.Array(links.toSeq*)
      )
      val _ = g.graphData(data)
    }

  private def showPopup(node: js.Dynamic, x: Double, y: Double): Unit =
    val title = node.selectDynamic("title").asInstanceOf[String]
    val state = node.selectDynamic("state").asInstanceOf[String]
    popupTitle.textContent = title

    if state == "error" then
      val errMsg = node.selectDynamic("errorMessage").asInstanceOf[js.UndefOr[String]].getOrElse("Failed to load page")
      popupError.textContent = errMsg
      popupError.style.display = "block"
      expandBtn.style.display = "none"
    else
      popupError.style.display = "none"
      expandBtn.style.display = "inline-block"
      if state == "expanded" then
        expandBtn.setAttribute("disabled", "true")
        expandBtn.textContent = "Expanded"
      else
        expandBtn.removeAttribute("disabled")
        expandBtn.textContent = "Expand"

    popup.style.display = "block"
    val rect = popup.getBoundingClientRect()
    val gap = 15.0

    val left =
      if x < dom.window.innerWidth / 2.0 then x + gap
      else x - rect.width - gap

    val top =
      if y < dom.window.innerHeight / 2.0 then y + gap
      else y - rect.height - gap

    popup.style.left = s"${left}px"
    popup.style.top = s"${top}px"

  private def hidePopup(): Unit =
    popup.style.display = "none"
    selectedNodeUrl = None

  private def handleNodeClick(node: js.Dynamic, event: dom.MouseEvent): Unit =
    val url = node.selectDynamic("url").asInstanceOf[String]
    if selectedNodeUrl.contains(url) then
      hidePopup()
    else
      selectedNodeUrl = Some(url)
      showPopup(node, event.clientX.toDouble, event.clientY.toDouble)

  private def handleToolResultData(result: CallToolResult): Unit =
    if !result.isError.getOrElse(false) then
      val resOpt = result.structuredContent.flatMap(_.as[GetFirstDegreeLinksResult].toOption)
        .orElse(result.content.collectFirst { case ContentBlock.Text(text, _, _) =>
          io.circe.parser.decode[GetFirstDegreeLinksResult](text).toOption
        }.flatten)

      resOpt.foreach { res =>
        val page = res.page
        val _ = addNode(page.url, page.title)
        updateNodeTitle(page.url, page.title)

        res.error match
          case Some(err) =>
            setNodeState(page.url, "error", Some(err))
          case None =>
            val sourceNode = nodes.find(n => n.selectDynamic("url").asInstanceOf[String] == page.url)
            val sourcePos = sourceNode.flatMap { n =>
              val x = n.selectDynamic("x").asInstanceOf[js.UndefOr[Double]].toOption
              val y = n.selectDynamic("y").asInstanceOf[js.UndefOr[Double]].toOption
              x.flatMap(xv => y.map(yv => (xv, yv)))
            }

            for link <- res.links do
              val _ = addNode(link.url, link.title, "default", sourcePos)
              val _ = addEdge(page.url, link.url)

            setNodeState(page.url, "expanded")

        updateGraph()
      }

  private def expandUrl(url: String, view: McpAppView[IO]): IO[Int] =
    view.callServerTool("get-first-degree-links", JsonObject("url" -> Json.fromString(url)))
      .flatMap { res =>
        IO {
          graphOpt.foreach(_.warmupTicks(0))
          handleToolResultData(res)
          val linksAdded = res.structuredContent.flatMap(_.as[GetFirstDegreeLinksResult].toOption)
            .map(_.links.size)
            .getOrElse(0)
          linksAdded
        }
      }
      .handleErrorWith { err =>
        IO {
          setNodeState(url, "error", Some(err.getMessage))
          updateGraph()
          0
        }
      }

  override def tools: IO[Option[ViewToolProvider[IO]]] =
    ViewTools.of[IO](
      ViewTool[SearchArticleArgs](
        "search-article",
        "Search for a Wikipedia article and add it to the graph as the new starting point",
        title = Some("Search Article")
      ) { args =>
        currentView match
          case None => IO.pure(CallToolResult.structured("View not ready", SearchArticleResult(success = false, error = Some("View not ready")).asJson))
          case Some(v) =>
            val searchUrl = s"https://en.wikipedia.org/wiki/Special:Search?go=Go&search=${js.URIUtils.encodeURIComponent(args.query)}"
            v.callServerTool("get-first-degree-links", JsonObject("url" -> Json.fromString(searchUrl)))
              .map { result =>
                val resOpt = result.structuredContent.flatMap(_.as[GetFirstDegreeLinksResult].toOption)
                  .orElse(result.content.collectFirst { case ContentBlock.Text(text, _, _) =>
                    io.circe.parser.decode[GetFirstDegreeLinksResult](text).toOption
                  }.flatten)

                resOpt match
                  case Some(res) if res.error.isEmpty =>
                    nodes.clear()
                    links.clear()
                    initialUrl = Some(res.page.url)
                    val _ = addNode(res.page.url, res.page.title, "default", Some((0.0, 0.0)))
                    graphOpt.foreach(_.warmupTicks(100))
                    handleToolResultData(result)
                    graphOpt.foreach(_.centerAt(0.0, 0.0, 500.0))

                    val r = SearchArticleResult(
                      success = true,
                      article = Some(res.page),
                      linksFound = res.links.size
                    )
                    CallToolResult.structured(s"Navigated to article: ${res.page.title}", r.asJson)

                  case _ =>
                    val r = SearchArticleResult(
                      success = false,
                      error = Some(s"Could not find article for: ${args.query}")
                    )
                    CallToolResult.structured(s"Could not find article for: ${args.query}", r.asJson)
              }
      },
      ViewTool[NoArgs](
        "get-current-article",
        "Get information about the currently selected or initial article in the graph",
        title = Some("Get Current Article")
      ) { _ =>
        IO {
          val currentUrl = selectedNodeUrl.orElse(initialUrl)
          val nodeOpt = currentUrl.flatMap(u => nodes.find(n => n.selectDynamic("url").asInstanceOf[String] == u))

          nodeOpt match
            case None =>
              val res = CurrentArticleResult(hasSelection = false, article = None)
              CallToolResult.structured("No article is currently selected", res.asJson)
            case Some(node) =>
              val url = node.selectDynamic("url").asInstanceOf[String]
              val title = node.selectDynamic("title").asInstanceOf[String]
              val state = node.selectDynamic("state").asInstanceOf[String]
              val errMsg = node.selectDynamic("errorMessage").asInstanceOf[js.UndefOr[String]].toOption
              val info = CurrentArticleInfo(
                url = url,
                title = title,
                state = state,
                isExpanded = state == "expanded",
                hasError = state == "error",
                errorMessage = errMsg
              )
              val res = CurrentArticleResult(hasSelection = true, article = Some(info))
              CallToolResult.structured(s"Current article: $title\nURL: $url\nState: $state", res.asJson)
        }
      },
      ViewTool[IdentifierArgs](
        "highlight-node",
        "Highlight and center on a specific node in the graph by title or URL",
        title = Some("Highlight Node")
      ) { args =>
        IO {
          val lower = args.identifier.toLowerCase
          val nodeOpt = nodes.find { n =>
            val url = n.selectDynamic("url").asInstanceOf[String]
            val title = n.selectDynamic("title").asInstanceOf[String]
            url == args.identifier || title.toLowerCase.contains(lower)
          }

          nodeOpt match
            case None =>
              val res = HighlightNodeResult(
                success = false,
                error = Some("Node not found in graph"),
                availableNodes = Some(nodes.map(_.selectDynamic("title").asInstanceOf[String]).toList)
              )
              CallToolResult.structured(s"Node not found: ${args.identifier}", res.asJson)
            case Some(node) =>
              val url = node.selectDynamic("url").asInstanceOf[String]
              val title = node.selectDynamic("title").asInstanceOf[String]
              val x = node.selectDynamic("x").asInstanceOf[js.UndefOr[Double]].toOption
              val y = node.selectDynamic("y").asInstanceOf[js.UndefOr[Double]].toOption

              selectedNodeUrl = Some(url)
              for (xv <- x; yv <- y) do
                graphOpt.foreach { g =>
                  val _ = g.centerAt(xv, yv, 500.0)
                  val _ = g.zoom(2.0, 500.0)
                }

              val res = HighlightNodeResult(
                success = true,
                node = Some(PageInfo(url, title))
              )
              CallToolResult.structured(s"Highlighted node: $title", res.asJson)
        }
      },
      ViewTool[IdentifierArgs](
        "expand-node",
        "Expand a node to fetch and display all Wikipedia pages it links to. This is the core way to explore the graph.",
        title = Some("Expand Node")
      ) { args =>
        val lower = args.identifier.toLowerCase
        val nodeOpt = nodes.find { n =>
          val url = n.selectDynamic("url").asInstanceOf[String]
          val title = n.selectDynamic("title").asInstanceOf[String]
          url == args.identifier || title.toLowerCase.contains(lower)
        }

        nodeOpt match
          case None =>
            IO.pure {
              val res = ExpandNodeResult(
                success = false,
                error = Some("Node not found in graph"),
                availableNodes = Some(nodes.map(_.selectDynamic("title").asInstanceOf[String]).toList)
              )
              CallToolResult.structured(s"Node not found: ${args.identifier}", res.asJson)
            }
          case Some(node) =>
            val url = node.selectDynamic("url").asInstanceOf[String]
            val title = node.selectDynamic("title").asInstanceOf[String]
            val state = node.selectDynamic("state").asInstanceOf[String]

            if state == "expanded" then
              IO.pure {
                val res = ExpandNodeResult(
                  success = true,
                  alreadyExpanded = Some(true),
                  node = Some(PageInfo(url, title))
                )
                CallToolResult.structured(s"""Node "$title" is already expanded""", res.asJson)
              }
            else if state == "error" then
              val errMsg = node.selectDynamic("errorMessage").asInstanceOf[js.UndefOr[String]].getOrElse("Unknown error")
              IO.pure {
                val res = ExpandNodeResult(
                  success = false,
                  error = Some(errMsg)
                )
                CallToolResult.structured(s"""Node "$title" has an error: $errMsg""", res.asJson)
              }
            else
              currentView match
                case None =>
                  IO.pure(CallToolResult.structured("View not ready", ExpandNodeResult(success = false, error = Some("View not ready")).asJson))
                case Some(v) =>
                  expandUrl(url, v).map { linksAdded =>
                    val x = node.selectDynamic("x").asInstanceOf[js.UndefOr[Double]].toOption
                    val y = node.selectDynamic("y").asInstanceOf[js.UndefOr[Double]].toOption
                    for (xv <- x; yv <- y) do
                      graphOpt.foreach(_.centerAt(xv, yv, 500.0))

                    val res = ExpandNodeResult(
                      success = true,
                      node = Some(PageInfo(url, title)),
                      linksAdded = linksAdded
                    )
                    CallToolResult.structured(s"""Expanded "$title" - found $linksAdded linked articles""", res.asJson)
                  }
      },
      ViewTool[NoArgs](
        "get-visible-nodes",
        "Get a list of all nodes currently visible in the graph",
        title = Some("Get Visible Nodes")
      ) { _ =>
        IO {
          val list = nodes.map { n =>
            val url = n.selectDynamic("url").asInstanceOf[String]
            val title = n.selectDynamic("title").asInstanceOf[String]
            val state = n.selectDynamic("state").asInstanceOf[String]
            VisibleNodeInfo(
              url = url,
              title = title,
              state = state,
              isExpanded = state == "expanded",
              hasError = state == "error"
            )
          }.toList

          val expandedCount = list.count(_.isExpanded)
          val errorCount = list.count(_.hasError)
          val res = VisibleNodesResult(
            totalNodes = list.size,
            expandedNodes = expandedCount,
            errorNodes = errorCount,
            nodes = list
          )
          val summary = s"Graph contains ${list.size} nodes:\n" + list.map(n => s"- ${n.title} (${n.state})").mkString("\n")
          CallToolResult.structured(summary, res.asJson)
        }
      }
    ).map(Some(_))

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    IO {
      currentView = Some(view)

      val g = new ForceGraph(container)
        .nodeId("url")
        .nodeLabel("title")
        .nodeColor { (node: js.Dynamic) =>
          val state = node.selectDynamic("state").asInstanceOf[String]
          state match
            case "expanded" => getCssColor("--node-expanded")
            case "error"    => getCssColor("--node-error")
            case _          => getCssColor("--node-default")
        }
        .nodeVal(8.0)
        .linkDirectionalArrowLength(6.0)
        .linkDirectionalArrowRelPos(1.0)
        .linkColor(_ => getCssColor("--link-color"))
        .onNodeClick { (node, event) =>
          handleNodeClick(node, event)
        }
        .onBackgroundClick(() => hidePopup())
        .d3Force("charge", D3Force3D.forceManyBody().strength(-80))
        .d3Force("link", D3Force3D.forceLink().distance(60))
        .d3Force("collide", D3Force3D.forceCollide(12))
        .d3Force("center", D3Force3D.forceCenter())
        .d3VelocityDecay(0.3)
        .cooldownTime(Double.PositiveInfinity)
        .d3AlphaMin(0.0)

      val ambientFn: js.Function0[Unit] = () => {
        for n <- nodes do
          val vx = n.selectDynamic("vx").asInstanceOf[js.UndefOr[Double]].getOrElse(0.0)
          val vy = n.selectDynamic("vy").asInstanceOf[js.UndefOr[Double]].getOrElse(0.0)
          n.updateDynamic("vx")(vx + (scala.util.Random.nextDouble() - 0.5) * 0.1)
          n.updateDynamic("vy")(vy + (scala.util.Random.nextDouble() - 0.5) * 0.1)
      }
      val _ = g.d3Force("ambient", ambientFn)
      val _ = g.graphData(js.Dynamic.literal("nodes" -> js.Array(), "links" -> js.Array()))

      graphOpt = Some(g)

      def handleResize(): Unit =
        val rect = container.getBoundingClientRect()
        val _ = g.width(rect.width).height(rect.height)

      dom.window.addEventListener("resize", (_: dom.Event) => handleResize())
      handleResize()

      dom.document.addEventListener("keydown", (e: dom.KeyboardEvent) => {
        if e.key == "Escape" && popup.style.display == "block" then hidePopup()
      })

      ui.onClick(zoomInBtn) {
        IO {
          val cur = g.zoom()
          val _ = g.zoom(cur * 1.5, 200.0)
        }
      }

      ui.onClick(zoomOutBtn) {
        IO {
          val cur = g.zoom()
          val _ = g.zoom(cur / 1.5, 200.0)
        }
      }

      ui.onClick(openBtn) {
        selectedNodeUrl match
          case Some(u) =>
            view.openLink(u).handleErrorWith(_ => IO.unit) >> IO(hidePopup())
          case None => IO.unit
      }

      ui.onClick(expandBtn) {
        selectedNodeUrl match
          case Some(u) =>
            IO {
              expandBtn.setAttribute("disabled", "true")
              expandBtn.textContent = "Loading..."
            } >> expandUrl(u, view).attempt.flatMap { _ =>
              IO {
                expandBtn.removeAttribute("disabled")
                expandBtn.textContent = "Expand"
                hidePopup()
              }
            }
          case None => IO.unit
      }

      ui.onClick(resetBtn) {
        initialUrl match
          case Some(u) =>
            view.callServerTool("get-first-degree-links", JsonObject("url" -> Json.fromString(u)))
              .flatMap { res =>
                IO {
                  nodes.clear()
                  links.clear()
                  val _ = addNode(u, u, "default", Some((0.0, 0.0)))
                  g.warmupTicks(100)
                  handleToolResultData(res)
                  val _ = g.centerAt(0.0, 0.0, 500.0)
                }
              }
              .handleErrorWith(_ => IO.unit)
          case None => IO.unit
      }
    } >>
      view.events.evalMap {
        case HostEvent.ToolInput(arguments) =>
          IO {
            val urlOpt = arguments("url").flatMap(_.asString)
            urlOpt.foreach { u =>
              initialUrl = Some(u)
              val _ = addNode(u, u, "default", Some((0.0, 0.0)))
              graphOpt.foreach { g =>
                g.warmupTicks(100)
                updateGraph()
                val _ = g.centerAt(0.0, 0.0, 500.0)
              }
            }
          }

        case HostEvent.ToolResult(result) =>
          IO {
            graphOpt.foreach(_.warmupTicks(100))
            handleToolResultData(result)
          }

        case HostEvent.ToolInputPartial(_) => IO.unit
        case HostEvent.ToolCancelled(_)    => IO.unit
        case HostEvent.Unreadable(_, _, _) => IO.unit
      }.compile.drain
