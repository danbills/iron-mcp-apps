package ironmcp.extapps.say

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.github.iltotore.iron.*
import ironmcp.apps.*
import ironmcp.protocol.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.Method as HttpMethod
import org.http4s.client.Client
import org.typelevel.ci.CIString

class SaySuite extends CatsEffectSuite:

  private val meta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities.none
  )

  private val cannedPcm = Array.fill[Byte](4800)(0.toByte)

  private val mockRoutes = HttpRoutes.of[IO] {
    case req if req.method == HttpMethod.POST && req.uri.path.renderString.endsWith("/audio/speech") =>
      req.bodyText.compile.string.flatMap { _ =>
        IO.pure(
          Response[IO](Status.Ok)
            .withEntity(cannedPcm)
            .putHeaders(Header.Raw(CIString("X-Sample-Rate"), "24000"))
        )
      }
    case req if req.method == HttpMethod.GET && req.uri.path.renderString.endsWith("/voices") =>
      IO.pure(Response[IO](Status.Ok).withEntity("""{"predefined_voices":["cosette","alba"],"default_voice":"cosette"}"""))
    case req if req.method == HttpMethod.GET && req.uri.path.renderString.endsWith("/health") =>
      IO.pure(Response[IO](Status.Ok).withEntity("""{"status":"ok","sample_rate":24000}"""))
  }

  private val mockClient = Client.fromHttpApp[IO](mockRoutes.orNotFound)
  private val testUri    = Uri.unsafeFromString("http://mock-tts/v1")

  test("say server registers say tool and UI resource with CSP"):
    for
      server <- SayServer("<html>Say View</html>", mockClient, testUri)
      toolsResult <- server.tools.get.list(ListToolsParams(meta))
      sayOpt = toolsResult.tools.find(_.name.contains("say"))
      _ = assert(sayOpt.isDefined)
      sayTool = sayOpt.get
      toolUi = sayTool._meta.flatMap(_.get(McpApps.UiKey)).flatMap(_.as[UiToolMeta].toOption)
      _ = assertEquals(toolUi.flatMap(_.resourceUri), Some(SayServer.ResourceUri: String))

      // Check tool call returns viewUUID in meta
      callResult <- server.tools.get.call(CallToolParams(name = "say".refineUnsafe, _meta = meta))
      _ = assert(callResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, Some(m)) =>
              text.contains("Displayed a TTS view") && m.find(_._1.contains("viewUUID")).flatMap(_._2.asString).isDefined
            case _ => false
          }
        case _ => false
      )

      // Check UI resource contains CSP metadata
      resources = server.resources.get
      readResult <- resources.read(ReadResourceParams(uri = (SayServer.ResourceUri: String).assume, _meta = meta))
      resource = readResult match
        case r: ReadResourceResult => r.contents.headOption.get
        case _                     => fail("expected ReadResourceResult")
      resUi = resource match
        case t: ResourceContents.Text => t._meta.flatMap(_.get(McpApps.UiKey)).flatMap(_.as[UiResourceMeta].toOption)
        case _                        => None
      csp = resUi.flatMap(_.csp)
      _ = assert(csp.exists(_.resourceDomains.exists(_.contains("https://esm.sh"))))
    yield ()

  test("say server handles TTS queue lifecycle offline"):
    for
      server <- SayServer("<html>Say View</html>", mockClient, testUri)
      // 1. list_voices
      voicesResult <- server.tools.get.call(CallToolParams(name = "list_voices".refineUnsafe, _meta = meta))
      _ = assert(voicesResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, _) => text.contains("cosette") && text.contains("predefined_voices")
            case _                             => false
          }
        case _ => false
      )

      // 2. create_tts_queue
      createResult <- server.tools.get.call(
        CallToolParams(name = "create_tts_queue".refineUnsafe, arguments = Some(JsonObject("voice" -> Json.fromString("cosette"))), _meta = meta)
      )
      queueId = createResult match
        case r: CallToolResult =>
          r.content.headOption.flatMap {
            case ContentBlock.Text(text, _, _) =>
              io.circe.parser.decode[CreateQueueResult](text).toOption.map(_.queue_id)
            case _ => None
          }.getOrElse(fail("failed to parse create_tts_queue response"))
        case _ => fail("expected CallToolResult")

      // 3. add_tts_text
      addResult <- server.tools.get.call(
        CallToolParams(
          name = "add_tts_text".refineUnsafe,
          arguments = Some(JsonObject("queue_id" -> Json.fromString(queueId), "text" -> Json.fromString("Hello world! This is a test."))),
          _meta = meta
        )
      )
      _ = assert(addResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, _) => text.contains(""""queued":true""")
            case _                             => false
          }
        case _ => false
      )

      // 4. end_tts_queue
      endResult <- server.tools.get.call(
        CallToolParams(
          name = "end_tts_queue".refineUnsafe,
          arguments = Some(JsonObject("queue_id" -> Json.fromString(queueId))),
          _meta = meta
        )
      )
      _ = assert(endResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, _) => text.contains(""""ended":true""")
            case _                             => false
          }
        case _ => false
      )

      // 5. poll_tts_audio
      pollResult <- server.tools.get.call(
        CallToolParams(
          name = "poll_tts_audio".refineUnsafe,
          arguments = Some(JsonObject("queue_id" -> Json.fromString(queueId))),
          _meta = meta
        )
      )
      _ = assert(pollResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, _) =>
              val resp = io.circe.parser.decode[PollAudioResult](text).toOption
              resp.exists(r => r.done && r.chunks.nonEmpty && r.chunks.head.duration_ms > 0)
            case _ => false
          }
        case _ => false
      )

      // 6. cancel_tts_queue
      cancelResult <- server.tools.get.call(
        CallToolParams(
          name = "cancel_tts_queue".refineUnsafe,
          arguments = Some(JsonObject("queue_id" -> Json.fromString(queueId))),
          _meta = meta
        )
      )
      _ = assert(cancelResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, _) => text.contains(""""cancelled":true""")
            case _                             => false
          }
        case _ => false
      )
    yield ()

  test("say server synthesizes speech with live Pocket TTS on danarch (if reachable)"):
    import org.http4s.ember.client.EmberClientBuilder
    import scala.concurrent.duration.*
    EmberClientBuilder.default[IO].build.use { client =>
      val liveUri = Uri.unsafeFromString("http://100.94.119.53:8768/v1")
      client.status(Request[IO](HttpMethod.GET, Uri.unsafeFromString("http://100.94.119.53:8768/health"))).timeout(2.seconds).attempt.flatMap {
        case Right(status) if status.isSuccess =>
          for
            server <- SayServer("<html>Say View</html>", client, liveUri)
            createRes <- server.tools.get.call(
              CallToolParams(name = "create_tts_queue".refineUnsafe, arguments = Some(JsonObject("voice" -> Json.fromString("cosette"))), _meta = meta)
            )
            qid = createRes match
              case r: CallToolResult =>
                io.circe.parser.decode[CreateQueueResult](r.content.head.asInstanceOf[ContentBlock.Text].text).toOption.get.queue_id
              case _ => fail("expected CallToolResult")
            _ <- server.tools.get.call(
              CallToolParams(
                name = "add_tts_text".refineUnsafe,
                arguments = Some(JsonObject("queue_id" -> Json.fromString(qid), "text" -> Json.fromString("Live test on danarch."))),
                _meta = meta
              )
            )
            _ <- server.tools.get.call(
              CallToolParams(name = "end_tts_queue".refineUnsafe, arguments = Some(JsonObject("queue_id" -> Json.fromString(qid))), _meta = meta)
            )
            pollRes <- server.tools.get.call(
              CallToolParams(name = "poll_tts_audio".refineUnsafe, arguments = Some(JsonObject("queue_id" -> Json.fromString(qid))), _meta = meta)
            )
            _ = assert(pollRes match
              case r: CallToolResult =>
                val resp = io.circe.parser.decode[PollAudioResult](r.content.head.asInstanceOf[ContentBlock.Text].text).toOption
                resp.exists(p => p.chunks.nonEmpty && p.chunks.head.audio_base64.nonEmpty)
              case _ => false
            )
          yield ()
        case _ =>
          IO.println("Pocket TTS on danarch not reachable, skipping live synthesis check")
      }
    }
