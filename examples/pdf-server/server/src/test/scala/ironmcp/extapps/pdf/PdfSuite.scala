package ironmcp.extapps.pdf

import cats.effect.{IO, Ref}
import io.circe.JsonObject
import io.circe.syntax.*
import io.github.iltotore.iron.*
import ironmcp.apps.*
import ironmcp.protocol.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client

class PdfSuite extends CatsEffectSuite:

  private val meta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities.none
  )

  private val fakePdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".getBytes("UTF-8")

  private val cannedApp = HttpApp[IO] { req =>
    if req.uri.renderString.contains("arxiv.org") then
      IO.pure(Response[IO](Status.Ok).withEntity(fakePdfBytes))
    else
      IO.pure(Response[IO](Status.NotFound))
  }

  private val testClient = Client.fromHttpApp[IO](cannedApp)

  test("display_pdf returns viewUUID and has UI metadata") {
    for
      stateRef <- Ref.of[IO, PdfServerState](PdfServerState())
      server = PdfServer("<html></html>", testClient, stateRef)
      toolsResult <- server.tools.get.list(ListToolsParams(meta))
      dispTool = toolsResult.tools.find(_.name.contains("display_pdf")).get
      uiMeta = dispTool._meta.flatMap(_.get(McpApps.UiKey)).flatMap(_.as[UiToolMeta].toOption)
      _ = assertEquals(uiMeta.flatMap(_.resourceUri), Some(PdfServer.ResourceUri: String))
      resRaw <- server.tools.get.call(CallToolParams(name = "display_pdf".refineUnsafe, _meta = meta))
      res = resRaw.asInstanceOf[CallToolResult]
      _ = assertEquals(res.isError, None)
      block = res.content.head.asInstanceOf[ContentBlock.Text]
      _ = assert(block.text.contains("Displaying PDF:"))
      _ = assert(block._meta.isDefined)
      _ = assert(block._meta.get.exists(_._1.contains("viewUUID")))
    yield ()
  }

  test("list_pdfs returns available PDFs") {
    for
      stateRef <- Ref.of[IO, PdfServerState](PdfServerState())
      server = PdfServer("<html></html>", testClient, stateRef)
      resRaw <- server.tools.get.call(CallToolParams(name = "list_pdfs".refineUnsafe, arguments = Some(JsonObject.empty), _meta = meta))
      res = resRaw.asInstanceOf[CallToolResult]
      _ = assertEquals(res.isError, None)
      text = res.content.head.asInstanceOf[ContentBlock.Text].text
      _ = assert(text.contains("Attention Is All You Need"))
    yield ()
  }

  test("read_pdf_bytes streams range chunks") {
    val args = JsonObject(
      "source" -> PdfServer.DefaultPdf.asJson,
      "offset" -> 0L.asJson,
      "length" -> 16.asJson
    )
    for
      stateRef <- Ref.of[IO, PdfServerState](PdfServerState())
      server = PdfServer("<html></html>", testClient, stateRef)
      resRaw <- server.tools.get.call(CallToolParams(name = "read_pdf_bytes".refineUnsafe, arguments = Some(args), _meta = meta))
      res = resRaw.asInstanceOf[CallToolResult]
      _ = assertEquals(res.isError, None)
      parsed = res.content.collectFirst { case ContentBlock.Text(text, _, _) =>
        io.circe.parser.decode[ReadPdfBytesResult](text).toOption
      }.flatten
      _ = assert(parsed.isDefined)
      r = parsed.get
      _ = assertEquals(r.bytesRead, 16)
      _ = assertEquals(r.totalSize, fakePdfBytes.length.toLong)
    yield ()
  }

  test("interact queues commands and poll_pdf_commands drains them") {
    val uuid = "test-uuid-1234"
    val cmd = PdfCommand("navigate", page = Some(5), zoom = Some(1.5))
    val interactArgs = JsonObject(
      "viewUUID" -> uuid.asJson,
      "commands" -> List(cmd).asJson
    )
    val pollArgs = JsonObject(
      "viewUUID" -> uuid.asJson
    )

    for
      stateRef <- Ref.of[IO, PdfServerState](PdfServerState())
      server = PdfServer("<html></html>", testClient, stateRef)
      // Enqueue
      intResRaw <- server.tools.get.call(CallToolParams(name = "interact".refineUnsafe, arguments = Some(interactArgs), _meta = meta))
      intRes = intResRaw.asInstanceOf[CallToolResult]
      _ = assertEquals(intRes.isError, None)
      _ = assert(intRes.content.head.asInstanceOf[ContentBlock.Text].text.contains("Queued 1 command(s)"))

      // First poll drains
      poll1Raw <- server.tools.get.call(CallToolParams(name = "poll_pdf_commands".refineUnsafe, arguments = Some(pollArgs), _meta = meta))
      poll1 = poll1Raw.asInstanceOf[CallToolResult]
      p1 = io.circe.parser.decode[PollCommandsResult](poll1.content.head.asInstanceOf[ContentBlock.Text].text).toOption
      _ = assert(p1.isDefined)
      _ = assertEquals(p1.get.commands.size, 1)
      _ = assertEquals(p1.get.commands.head.page, Some(5))

      // Second poll is empty
      poll2Raw <- server.tools.get.call(CallToolParams(name = "poll_pdf_commands".refineUnsafe, arguments = Some(pollArgs), _meta = meta))
      poll2 = poll2Raw.asInstanceOf[CallToolResult]
      p2 = io.circe.parser.decode[PollCommandsResult](poll2.content.head.asInstanceOf[ContentBlock.Text].text).toOption
      _ = assert(p2.isDefined)
      _ = assertEquals(p2.get.commands, Nil)
    yield ()
  }
