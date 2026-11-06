package ironmcp.extapps.pdf

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, NoArgs, ViewPage}
import ironmcp.protocol.*
import ironmcp.server.*
import org.http4s.{Method, Request, Uri}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

import java.nio.file.{Files, Paths}
import java.util.Base64
import java.util.UUID

final case class PdfServerState(
    cachedFiles: Map[String, Array[Byte]] = Map.empty,
    pendingCommands: Map[String, List[PdfCommand]] = Map.empty,
    viewerStates: Map[String, PdfViewerState] = Map.empty
)

object PdfServer:

  val ResourceUri: UiUri = "ui://pdf-viewer/mcp-app.html"
  val DefaultPdf = "https://arxiv.org/pdf/1706.03762"

  def apply(html: String, client: Client[IO], stateRef: Ref[IO, PdfServerState]): McpServer[IO] =
    val cspMeta = UiResourceMeta(
      csp = Some(UiCsp(
        connectDomains = Some(List("https://unpkg.com", "https://arxiv.org")),
        resourceDomains = Some(List("https://unpkg.com", "https://arxiv.org"))
      ))
    )

    def getPdfBytes(source: String): IO[Array[Byte]] =
      stateRef.get.flatMap { st =>
        st.cachedFiles.get(source) match
          case Some(bytes) => IO.pure(bytes)
          case None =>
            if source.startsWith("http://") || source.startsWith("https://") then
              val hUri = Uri.unsafeFromString(source)
              val req = Request[IO](Method.GET, hUri)
              client.run(req).use { resp =>
                if resp.status.isSuccess then
                  resp.as[Array[Byte]].flatMap { bytes =>
                    stateRef.update(s => s.copy(cachedFiles = s.cachedFiles.updated(source, bytes))).as(bytes)
                  }
                else IO.raiseError(new RuntimeException(s"Failed to download PDF: HTTP ${resp.status.code}"))
              }
            else
              IO.blocking {
                val path = Paths.get(source)
                if Files.exists(path) then Files.readAllBytes(path)
                else Array.emptyByteArray
              }.flatMap { bytes =>
                if bytes.isEmpty then IO.raiseError(new RuntimeException(s"File not found: $source"))
                else stateRef.update(s => s.copy(cachedFiles = s.cachedFiles.updated(source, bytes))).as(bytes)
              }
      }

    val displayPdf = McpTool[DisplayPdfArgs](
      "display_pdf",
      "Show and render a PDF in an interactive viewer. Supports local paths and remote HTTPS URLs. Available tools for interaction: navigate, search, get_screenshot, get_viewer_state.",
      title = Some("Display PDF")
    ) { args =>
      val src = args.source.map(_.toString).getOrElse(DefaultPdf)
      val p = args.page.map(_.toInt).getOrElse(1)
      val rot = args.rotation.map(_.toInt).getOrElse(0)
      val uuid = UUID.randomUUID().toString

      val text = s"Displaying PDF: $src (page $p, rotation $rot, viewUUID: $uuid)"
      val meta: Map[MetaKey, Json] = Map("viewUUID".assume[MetaKeyC] -> Json.fromString(uuid))
      IO.pure(CallToolResult(List(ContentBlock.Text(text, _meta = Some(meta)))))
    }.withUi(ResourceUri)

    val listPdfs = McpTool[NoArgs](
      "list_pdfs",
      "List available PDFs that can be displayed",
      title = Some("List PDFs")
    ) { _ =>
      val listText = s"Available PDFs:\n1. Attention Is All You Need ($DefaultPdf)"
      IO.pure(CallToolResult(List(ContentBlock.Text(listText))))
    }

    val readPdfBytes = McpTool[ReadPdfBytesArgs](
      "read_pdf_bytes",
      "Read a range of bytes from a PDF (max 512KB per request). Used by the viewer.",
      title = Some("Read PDF Bytes")
    ) { args =>
      getPdfBytes(args.source).map { allBytes =>
        val total = allBytes.length.toLong
        val start = Math.min(args.offset.toInt, allBytes.length)
        val end = Math.min(start + Math.min(args.length, 512 * 1024), allBytes.length)
        val slice = allBytes.slice(start, end)
        val base64 = Base64.getEncoder.encodeToString(slice)
        val res = ReadPdfBytesResult(data = base64, totalSize = total, bytesRead = slice.length)
        CallToolResult(List(ContentBlock.Text(res.asJson.noSpaces)))
      }.handleError { err =>
        CallToolResult(List(ContentBlock.Text(s"Error reading PDF bytes: ${err.getMessage}")), isError = Some(true))
      }
    }.withVisibility(Visibility.App)

    val interact = McpTool[InteractArgs](
      "interact",
      "Interact with an existing PDF viewer: navigate, search, get viewer state. viewUUID must match the exact UUID returned by display_pdf.",
      title = Some("Interact with PDF")
    ) { args =>
      val uuid = args.viewUUID.toString
      val commands = args.commands.getOrElse {
        val singleAction = args.action.getOrElse("navigate")
        List(PdfCommand(
          action = singleAction,
          page = args.page,
          zoom = args.zoom,
          rotation = args.rotation,
          search = args.search
        ))
      }

      stateRef.update { s =>
        val current = s.pendingCommands.getOrElse(uuid, Nil)
        s.copy(pendingCommands = s.pendingCommands.updated(uuid, current ++ commands))
      }.as {
        CallToolResult(List(ContentBlock.Text(s"Queued ${commands.size} command(s) for viewer $uuid")))
      }
    }

    val pollPdfCommands = McpTool[PollCommandsArgs](
      "poll_pdf_commands",
      "Poll for pending commands for this viewer (used by viewer).",
      title = Some("Poll PDF Commands")
    ) { args =>
      val uuid = args.viewUUID
      stateRef.modify { s =>
        val cmds = s.pendingCommands.getOrElse(uuid, Nil)
        (s.copy(pendingCommands = s.pendingCommands - uuid), cmds)
      }.map { cmds =>
        val res = PollCommandsResult(cmds)
        CallToolResult(List(ContentBlock.Text(res.asJson.noSpaces)))
      }
    }.withVisibility(Visibility.App)

    val submitViewerState = McpTool[SubmitViewerStateArgs](
      "submit_viewer_state",
      "Submit a viewer-state snapshot (used by viewer).",
      title = Some("Submit Viewer State")
    ) { args =>
      stateRef.update { s =>
        s.copy(viewerStates = s.viewerStates.updated(args.viewUUID, args.state))
      }.as {
        CallToolResult(List(ContentBlock.Text("Submitted")))
      }
    }.withVisibility(Visibility.App)

    McpServer(
      info = Implementation(name = "PDF Server", version = "1.0.0"),
      tools = Some(ToolSet.of(displayPdf, listPdfs, readPdfBytes, interact, pollPdfCommands, submitViewerState)),
      resources = Some(
        UiResources.of[IO](
          UiResource(
            ResourceUri,
            "PDF Viewer UI",
            html,
            Some("Interactive PDF Viewer with chunked pagination"),
            cspMeta
          )
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] =
    for
      html     <- ViewPage.load("pdf-server/view")
      client   <- EmberClientBuilder.default[IO].build.allocated.map(_._1)
      stateRef <- Ref.of[IO, PdfServerState](PdfServerState())
    yield apply(html, client, stateRef)

object Main extends ExampleMain("pdf-server", _ => PdfServer.load)
