package ironmcp.extapps.pdf

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{ContentBlock, Implementation}
import org.scalajs.dom
import org.scalajs.dom.{HTMLButtonElement, HTMLCanvasElement, HTMLElement, HTMLInputElement}
import pdfjs.{PDFDocumentProxy, PdfJs}

import scala.concurrent.duration.*
import scala.scalajs.js

object PdfView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "PDF Viewer", version = "1.0.0")

  private val DefaultPdf = "https://arxiv.org/pdf/1706.03762"

  private var docOpt: Option[PDFDocumentProxy]    = None
  private var currentPage: Int                    = 1
  private var totalPages: Int                     = 1
  private var currentZoom: Double                 = 1.0
  private var currentRotation: Int                = 0
  private var currentSearch: Option[String]       = None
  private var viewUUID: Option[String]            = None
  private var currentDisplayMode: String          = "inline"
  private var isRendering: Boolean                = false

  private lazy val loadingEl: HTMLElement         = Dom.byId("loading")
  private lazy val errorEl: HTMLElement           = Dom.byId("error")
  private lazy val errorMsgEl: HTMLElement        = Dom.byId("error-message")
  private lazy val viewerEl: HTMLElement          = Dom.byId("viewer")
  private lazy val pdfTitleEl: HTMLElement        = Dom.byId("pdf-title")
  private lazy val prevBtn: HTMLButtonElement     = Dom.byId("prev-btn")
  private lazy val nextBtn: HTMLButtonElement     = Dom.byId("next-btn")
  private lazy val pageInput: HTMLInputElement    = Dom.byId("page-input")
  private lazy val totalPagesEl: HTMLElement      = Dom.byId("total-pages")
  private lazy val zoomOutBtn: HTMLButtonElement  = Dom.byId("zoom-out-btn")
  private lazy val zoomLevelEl: HTMLElement       = Dom.byId("zoom-level")
  private lazy val zoomInBtn: HTMLButtonElement   = Dom.byId("zoom-in-btn")
  private lazy val searchBtn: HTMLButtonElement   = Dom.byId("search-btn")
  private lazy val searchBar: HTMLElement         = Dom.byId("search-bar")
  private lazy val searchInput: HTMLInputElement  = Dom.byId("search-input")
  private lazy val searchCloseBtn: HTMLButtonElement = Dom.byId("search-close-btn")
  private lazy val fullscreenBtn: HTMLButtonElement  = Dom.byId("fullscreen-btn")
  private lazy val pdfCanvas: HTMLCanvasElement   = Dom.byId("pdf-canvas")

  private def showError(msg: String): Unit =
    loadingEl.style.display = "none"
    viewerEl.style.display = "none"
    errorMsgEl.textContent = msg
    errorEl.style.display = "flex"

  private def renderPage(pageNumber: Int): IO[Unit] =
    docOpt match
      case None => IO.unit
      case Some(doc) =>
        if isRendering then IO.unit
        else
          IO {
            isRendering = true
            currentPage = Math.max(1, Math.min(pageNumber, totalPages))
            pageInput.value = currentPage.toString
            totalPagesEl.textContent = s"of $totalPages"
            zoomLevelEl.textContent = s"${(currentZoom * 100).toInt}%"
          } >> IO.fromPromise(IO(doc.getPage(currentPage))).flatMap { page =>
            IO.async_[Unit] { cb =>
              val viewport = page.getViewport(js.Dynamic.literal("scale" -> currentZoom, "rotation" -> currentRotation))
              val w = viewport.width.asInstanceOf[Double].toInt
              val h = viewport.height.asInstanceOf[Double].toInt
              pdfCanvas.width = w
              pdfCanvas.height = h

              val ctx = pdfCanvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
              val renderTask = page.render(js.Dynamic.literal("canvasContext" -> ctx, "viewport" -> viewport))
              val promise = renderTask.promise.asInstanceOf[js.Promise[Unit]]
              val onOk: js.Function1[Unit, Unit] = _ => {
                isRendering = false
                cb(Right(()))
              }
              val onErr: js.Function1[Any, Unit] = err => {
                isRendering = false
                cb(Left(new RuntimeException(String.valueOf(err))))
              }
              val _ = promise.`then`[Unit](onOk, js.defined(onErr))
            }
          }.handleErrorWith { err =>
            IO {
              isRendering = false
              showError(s"Page render failed: ${err.getMessage}")
            }
          }

  private def loadPdfBytes(view: McpAppView[IO], source: String): IO[js.typedarray.Uint8Array] =
    def fetchChunk(offset: Long, acc: List[Array[Byte]]): IO[Array[Byte]] =
      val chunkSize = 512 * 1024
      val args = ReadPdfBytesArgs(source, offset, chunkSize)
      view.callServerTool("read_pdf_bytes", args.asJsonObject).flatMap { res =>
        val parsed = res.content.collectFirst { case ContentBlock.Text(text, _, _) =>
          io.circe.parser.decode[ReadPdfBytesResult](text).toOption
        }.flatten.orElse(res.structuredContent.flatMap(_.as[ReadPdfBytesResult].toOption))

        parsed match
          case None =>
            IO.raiseError(new RuntimeException("Invalid response from read_pdf_bytes"))
          case Some(r) =>
            val binaryStr = dom.window.atob(r.data)
            val bytes = new Array[Byte](binaryStr.length)
            var i = 0
            while i < binaryStr.length do
              bytes(i) = binaryStr.charAt(i).toByte
              i += 1

            val newAcc = bytes :: acc
            val newOffset = offset + r.bytesRead
            if newOffset >= r.totalSize || r.bytesRead == 0 then
              IO.pure(newAcc.reverse.toArray.flatten)
            else
              fetchChunk(newOffset, newAcc)
      }

    fetchChunk(0L, Nil).map { allBytes =>
      val uint8 = new js.typedarray.Uint8Array(allBytes.length)
      var i = 0
      while i < allBytes.length do
        uint8(i) = allBytes(i).toShort
        i += 1
      uint8
    }

  private def loadDocument(view: McpAppView[IO], source: String, initialPage: Int, rotation: Int): IO[Unit] =
    IO {
      loadingEl.style.display = "flex"
      errorEl.style.display = "none"
      viewerEl.style.display = "none"
      currentRotation = rotation
      val name = source.split("/").lastOption.getOrElse("Document")
      pdfTitleEl.textContent = name
    } >>
      loadPdfBytes(view, source).attempt.flatMap {
        case Left(err) =>
          IO(showError(s"Failed to stream PDF bytes: ${err.getMessage}"))
        case Right(uint8) =>
          val loadTask = PdfJs.getDocument(js.Dynamic.literal("data" -> uint8))
          val promise = loadTask.promise.asInstanceOf[js.Promise[PDFDocumentProxy]]
          IO.fromPromise(IO(promise)).flatMap { doc =>
            IO {
              docOpt = Some(doc)
              totalPages = doc.numPages
              loadingEl.style.display = "none"
              viewerEl.style.display = "flex"
            } >> renderPage(initialPage)
          }.handleErrorWith { err =>
            IO(showError(s"PDF parsing failed: ${err.getMessage}"))
          }
      }

  private def executeCommand(cmd: PdfCommand, view: McpAppView[IO]): IO[Unit] =
    cmd.action match
      case "navigate" =>
        IO {
          cmd.zoom.foreach(z => currentZoom = z)
          cmd.rotation.foreach(r => currentRotation = r)
        } >> renderPage(cmd.page.getOrElse(currentPage))

      case "search" =>
        IO {
          currentSearch = cmd.search
          cmd.search.foreach { term =>
            searchBar.style.display = "flex"
            searchInput.value = term
          }
        }

      case "get_viewer_state" =>
        viewUUID match
          case None => IO.unit
          case Some(uuid) =>
            val state = PdfViewerState(currentPage, totalPages, currentZoom, currentRotation, currentSearch)
            val args = SubmitViewerStateArgs(uuid, state)
            view.callServerTool("submit_viewer_state", args.asJsonObject).void.handleErrorWith(_ => IO.unit)

      case _ => IO.unit

  private def pollCommandsLoop(view: McpAppView[IO]): IO[Unit] =
    (for
      _ <- IO.sleep(500.millis)
      _ <- viewUUID match
        case None => IO.unit
        case Some(uuid) =>
          val args = PollCommandsArgs(uuid)
          view.callServerTool("poll_pdf_commands", args.asJsonObject).flatMap { res =>
            val parsed = res.content.collectFirst { case ContentBlock.Text(text, _, _) =>
              io.circe.parser.decode[PollCommandsResult](text).toOption
            }.flatten.orElse(res.structuredContent.flatMap(_.as[PollCommandsResult].toOption))

            parsed match
              case Some(r) => r.commands.traverse_(executeCommand(_, view))
              case None    => IO.unit
          }.handleErrorWith(_ => IO.unit)
    yield ()).foreverM

  private def toggleFullscreen(view: McpAppView[IO]): IO[Unit] =
    val targetMode = if currentDisplayMode == "fullscreen" then DisplayMode.`inline` else DisplayMode.fullscreen
    view.requestDisplayMode(targetMode).map { chosen =>
      currentDisplayMode = chosen.asJson.asString.getOrElse("inline")
      if currentDisplayMode == "fullscreen" then
        viewerEl.classList.add("fullscreen")
      else
        viewerEl.classList.remove("fullscreen")
    }.handleErrorWith(_ => IO.unit)

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    ui.onTeardown(IO {
      docOpt.foreach { doc =>
        val _ = doc.destroy()
      }
      docOpt = None
    }) >>
      IO {
        PdfJs.GlobalWorkerOptions.workerSrc = "https://unpkg.com/pdfjs-dist@5.4.530/build/pdf.worker.min.mjs"

        ui.onClick(prevBtn)(renderPage(currentPage - 1))
        ui.onClick(nextBtn)(renderPage(currentPage + 1))
        ui.onClick(zoomInBtn) {
          IO { currentZoom = Math.min(3.0, currentZoom + 0.25) } >> renderPage(currentPage)
        }
        ui.onClick(zoomOutBtn) {
          IO { currentZoom = Math.max(0.5, currentZoom - 0.25) } >> renderPage(currentPage)
        }
        ui.onClick(searchBtn) {
          IO {
            val isVis = searchBar.style.display != "none"
            searchBar.style.display = if isVis then "none" else "flex"
            if !isVis then searchInput.focus()
          }
        }
        ui.onClick(searchCloseBtn) {
          IO { searchBar.style.display = "none" }
        }
        ui.onClick(fullscreenBtn)(toggleFullscreen(view))

        pageInput.addEventListener("change", (_: dom.Event) => {
          val p = pageInput.value.toIntOption.getOrElse(currentPage)
          val _ = renderPage(p).unsafeRunAndForget()(using cats.effect.unsafe.implicits.global)
        })

        dom.document.addEventListener("keydown", (e: dom.KeyboardEvent) => {
          if e.key == "Escape" && currentDisplayMode == "fullscreen" then
            val _ = toggleFullscreen(view).unsafeRunAndForget()(using cats.effect.unsafe.implicits.global)
        })
      } >>
      pollCommandsLoop(view).background.surround {
        view.events.evalMap {
          case HostEvent.ToolInput(arguments) =>
            val src = arguments("source").flatMap(_.asString).getOrElse(DefaultPdf)
            val p = arguments("page").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(1)
            val rot = arguments("rotation").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
            loadDocument(view, src, p, rot)

          case HostEvent.ToolResult(result) =>
            IO {
              val uuidOpt = result.content.headOption.flatMap {
                case ContentBlock.Text(_, _, Some(meta)) => meta.find(_._1.contains("viewUUID")).flatMap(_._2.asString)
                case _                                   => None
              }
              uuidOpt.foreach(u => viewUUID = Some(u))
            }

          case HostEvent.ToolInputPartial(_) => IO.unit
          case HostEvent.ToolCancelled(_)    => IO.unit
          case HostEvent.Unreadable(_, _, _) => IO.unit
        }.compile.drain
      }
