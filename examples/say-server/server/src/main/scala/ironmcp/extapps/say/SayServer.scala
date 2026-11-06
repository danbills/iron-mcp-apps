package ironmcp.extapps.say

import cats.effect.{IO, Ref}
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, NoArgs, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.{Default, JsonSchemaOf}
import ironmcp.server.*
import org.http4s.{Method, Request, Uri}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

import java.util.Base64

final case class AudioChunkData(
    index: Int,
    audio_base64: String,
    char_start: Int,
    char_end: Int,
    duration_ms: Double
) derives Encoder.AsObject, Decoder

final case class CreateQueueResult(
    queue_id: String,
    sample_rate: Int = 24000
) derives Encoder.AsObject, Decoder

final case class PollAudioResult(
    chunks: List[AudioChunkData],
    done: Boolean,
    status: String,
    error: Option[String] = None
) derives Encoder.AsObject, Decoder

final case class QueueState(
    id: String,
    voice: String,
    sampleRate: Int = 24000,
    status: String = "active", // active | complete | error
    errorMessage: Option[String] = None,
    buffer: String = "",
    endSignaled: Boolean = false,
    charOffset: Int = 0,
    chunkIndex: Int = 0,
    audioChunks: List[AudioChunkData] = Nil,
    chunksDelivered: Int = 0,
    lastActivity: Long = System.currentTimeMillis()
)

final case class SayArgs(
    text: Option[String :| (Default["Hello! I'm a text-to-speech demonstration. This speech is being generated in real-time as you watch. The words you see highlighted are synchronized with the audio playback, creating a karaoke-style reading experience. You can click to pause or resume, and use the reset button to restart from the beginning. Pretty neat, right?"] DescribedAs "The English text to speak aloud")] = None,
    voice: Option[String :| (Default["cosette"] DescribedAs "Voice to use. Can be a predefined name (alba, marius, cosette, etc.), a HuggingFace URL (hf://kyutai/tts-voices/...), or a local file path.")] = None,
    autoPlay: Option[Boolean :| (Default[true] DescribedAs "Whether to start playing automatically. Note: browsers may block autoplay until user interaction.")] = None
) derives Decoder, JsonSchemaOf

final case class CreateQueueArgs(
    voice: Option[String :| Default["cosette"]] = None
) derives Decoder, JsonSchemaOf

final case class AddTextArgs(
    queue_id: String,
    text: String
) derives Decoder, JsonSchemaOf

final case class EndQueueArgs(
    queue_id: String
) derives Decoder, JsonSchemaOf

final case class CancelQueueArgs(
    queue_id: String
) derives Decoder, JsonSchemaOf

final case class PollAudioArgs(
    queue_id: String
) derives Decoder, JsonSchemaOf

object SayServer:

  val ResourceUri: UiUri = "ui://say-demo/view.html"

  val DefaultText: String =
    "Hello! I'm a text-to-speech demonstration. This speech is being generated in real-time as you watch. The words you see highlighted are synchronized with the audio playback, creating a karaoke-style reading experience. You can click to pause or resume, and use the reset button to restart from the beginning. Pretty neat, right?"

  val PredefinedVoices: Map[String, String] = Map(
    "alba"    -> "hf://kyutai/tts-voices/alba-mackenna/casual.wav",
    "marius"  -> "hf://kyutai/tts-voices/alba-mackenna/merchant.wav",
    "javert"  -> "hf://kyutai/tts-voices/alba-mackenna/announcer.wav",
    "jean"    -> "hf://kyutai/tts-voices/alba-mackenna/a-moment-by.wav",
    "fantine" -> "hf://kyutai/tts-voices/vctk/p225_023_mic1.wav",
    "cosette" -> "hf://kyutai/tts-voices/vctk/p226_023_mic1.wav",
    "eponine" -> "hf://kyutai/tts-voices/vctk/p227_023_mic1.wav",
    "azelma"  -> "hf://kyutai/tts-voices/vctk/p228_023_mic1.wav"
  )

  val DefaultVoice = "cosette"

  private val voicesJson = Json.obj(
    "predefined_voices" -> PredefinedVoices.keys.toList.asJson,
    "default_voice"     -> Json.fromString(DefaultVoice),
    "custom_voice_formats" -> List(
      "hf://kyutai/tts-voices/<collection>/<file>.wav",
      "/path/to/local/voice.wav"
    ).asJson,
    "collections" -> List(
      "alba-mackenna (CC-BY 4.0) - voice-acted characters",
      "vctk (CC-BY 4.0) - VCTK dataset speakers",
      "cml-tts/fr (CC-BY 4.0) - French voices",
      "voice-donations (CC0) - public domain community voices",
      "expresso (CC-BY-NC 4.0) - expressive (NON-COMMERCIAL ONLY)",
      "ears (CC-BY-NC 4.0) - emotional (NON-COMMERCIAL ONLY)"
    ).asJson
  ).spaces2

  private def extractChunks(text: String, minChars: Int = 40, maxChars: Int = 180): (List[String], String) =
    val sentenceRegex = """(?<=[.!?])\s+""".r
    val parts = sentenceRegex.split(text).toList
    if parts.isEmpty then (Nil, "")
    else if parts.size == 1 then
      val single = parts.head
      if single.length >= maxChars then
        val splitAt = single.lastIndexOf(' ', maxChars)
        val idx = if splitAt > minChars then splitAt else maxChars
        (List(single.substring(0, idx)), single.substring(idx).trim)
      else (Nil, single)
    else
      val ready = parts.init
      val rest  = parts.last
      (ready, rest)

  def apply(html: String, client: Client[IO], ttsBaseUri: Uri): IO[McpServer[IO]] =
    for
      queuesRef <- Ref.of[IO, Map[String, QueueState]](Map.empty)
    yield
      def callTts(text: String, voice: String): IO[Array[Byte]] =
        val reqBody = Json.obj(
          "input"           -> Json.fromString(text),
          "voice"           -> Json.fromString(voice),
          "response_format" -> Json.fromString("pcm"),
          "stream"          -> Json.fromBoolean(false)
        ).noSpaces
        val speechUri = ttsBaseUri / "audio" / "speech"
        val req = Request[IO](Method.POST, speechUri)
          .withEntity(reqBody)
          .putHeaders(org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))

        client.run(req).use { resp =>
          if resp.status.isSuccess then resp.as[Array[Byte]]
          else resp.bodyText.compile.string.flatMap(err => IO.raiseError(new RuntimeException(s"TTS error ${resp.status}: $err")))
        }

      def processText(queueId: String, textToAdd: String, forceFlush: Boolean): IO[Unit] =
        queuesRef.get.flatMap { queues =>
          queues.get(queueId) match
            case None => IO.unit
            case Some(st) if st.endSignaled && !forceFlush => IO.unit
            case Some(st) =>
              val newBuffer = st.buffer + textToAdd
              val (readyChunks, remaining) =
                if forceFlush then
                  val trimmed = newBuffer.trim
                  if trimmed.nonEmpty then (List(trimmed), "") else (Nil, "")
                else extractChunks(newBuffer)

              if readyChunks.isEmpty then
                queuesRef.update(_.updated(queueId, st.copy(buffer = remaining, lastActivity = System.currentTimeMillis())))
              else
                readyChunks.foldLeft(IO.pure(st.copy(buffer = remaining))) { (accIO, chunkText) =>
                  accIO.flatMap { currSt =>
                    val chunkStart = currSt.charOffset
                    val chunkEnd   = currSt.charOffset + chunkText.length
                    callTts(chunkText, currSt.voice)
                      .map { pcmBytes =>
                        val numSamples = pcmBytes.length / 2.0
                        val durationMs = (numSamples / currSt.sampleRate.toDouble) * 1000.0
                        val b64 = Base64.getEncoder.encodeToString(pcmBytes)
                        val chunkData = AudioChunkData(currSt.chunkIndex, b64, chunkStart, chunkEnd, durationMs)
                        currSt.copy(
                          charOffset = chunkEnd,
                          chunkIndex = currSt.chunkIndex + 1,
                          audioChunks = currSt.audioChunks :+ chunkData,
                          lastActivity = System.currentTimeMillis()
                        )
                      }
                      .handleErrorWith { err =>
                        IO.pure(currSt.copy(status = "error", errorMessage = Some(err.getMessage), lastActivity = System.currentTimeMillis()))
                      }
                  }
                }.flatMap { finalSt =>
                  val updated = if forceFlush then finalSt.copy(status = if finalSt.status == "error" then "error" else "complete", endSignaled = true) else finalSt
                  queuesRef.update(_.updated(queueId, updated))
                }
        }

      val listVoices = McpTool[NoArgs](
        "list_voices",
        "List available TTS voices. Returns the predefined voice names that can be used with the say tool.",
        title = Some("List Voices")
      )(_ => IO.pure(CallToolResult(List(ContentBlock.Text(voicesJson)))))

      val say = McpTool[SayArgs](
        "say",
        """Speak English text aloud using text-to-speech. Audio streams in real-time as text is provided. Use list_voices() for voice options.""",
        title = Some("Say")
      ) { args =>
        val voice = args.voice.fold(DefaultVoice)(v => v)
        val uuid  = java.util.UUID.randomUUID().toString.replace("-", "").take(12)
        IO.pure(
          CallToolResult(
            List(
              ContentBlock.Text(
                s"Displayed a TTS view with voice '$voice'. Click to play/pause, use toolbar to restart or fullscreen.",
                _meta = Some(Map("viewUUID".assume[MetaKeyC] -> Json.fromString(uuid)))
              )
            )
          )
        )
      }.withUi(ResourceUri)

      val createTtsQueue = McpTool[CreateQueueArgs](
        "create_tts_queue",
        "Create a TTS generation queue. Returns queue_id and sample_rate. App-only.",
        title = Some("Create TTS Queue")
      ) { args =>
        val voice   = args.voice.fold(DefaultVoice)(v => v)
        val queueId = java.util.UUID.randomUUID().toString.replace("-", "").take(12)
        val sampleRate = 24000
        val st = QueueState(id = queueId, voice = voice, sampleRate = sampleRate)
        queuesRef.update(_.updated(queueId, st)).as(
          CallToolResult(List(ContentBlock.Text(CreateQueueResult(queueId, sampleRate).asJson.noSpaces)))
        )
      }.withVisibility(Visibility.App)

      val addTtsText = McpTool[AddTextArgs](
        "add_tts_text",
        "Add text to a TTS queue. App-only.",
        title = Some("Add TTS Text")
      ) { args =>
        queuesRef.get.flatMap { queues =>
          queues.get(args.queue_id) match
            case None =>
              IO.pure(CallToolResult(List(ContentBlock.Text("""{"error":"Queue not found"}"""))))
            case Some(st) if st.endSignaled =>
              IO.pure(CallToolResult(List(ContentBlock.Text("""{"error":"Queue already ended"}"""))))
            case Some(_) =>
              processText(args.queue_id, args.text, forceFlush = false).as(
                CallToolResult(List(ContentBlock.Text("""{"queued":true}""")))
              )
        }
      }.withVisibility(Visibility.App)

      val endTtsQueue = McpTool[EndQueueArgs](
        "end_tts_queue",
        "Signal that no more text will be sent to a queue. App-only.",
        title = Some("End TTS Queue")
      ) { args =>
        queuesRef.get.flatMap { queues =>
          queues.get(args.queue_id) match
            case None =>
              IO.pure(CallToolResult(List(ContentBlock.Text("""{"error":"Queue not found"}"""))))
            case Some(st) if st.endSignaled =>
              IO.pure(CallToolResult(List(ContentBlock.Text("""{"already_ended":true}"""))))
            case Some(_) =>
              processText(args.queue_id, "", forceFlush = true).as(
                CallToolResult(List(ContentBlock.Text("""{"ended":true}""")))
              )
        }
      }.withVisibility(Visibility.App)

      val cancelTtsQueue = McpTool[CancelQueueArgs](
        "cancel_tts_queue",
        "Cancel and cleanup a TTS queue. App-only.",
        title = Some("Cancel TTS Queue")
      ) { args =>
        queuesRef.modify { queues =>
          (queues.removed(args.queue_id), queues.contains(args.queue_id))
        }.map { existed =>
          if existed then CallToolResult(List(ContentBlock.Text("""{"cancelled":true}""")))
          else CallToolResult(List(ContentBlock.Text("""{"error":"Queue not found"}""")))
        }
      }.withVisibility(Visibility.App)

      val pollTtsAudio = McpTool[PollAudioArgs](
        "poll_tts_audio",
        "Poll for available audio chunks from a TTS queue. App-only.",
        title = Some("Poll TTS Audio")
      ) { args =>
        val now = System.currentTimeMillis()
        queuesRef.modify { queues =>
          queues.get(args.queue_id) match
            case None =>
              (queues, Left("Queue not found"))
            case Some(st) =>
              val timedOut = (now - st.lastActivity) > 30000L && st.status == "active"
              val effSt    = if timedOut then st.copy(status = "error", errorMessage = Some("Queue timeout: 30s inactivity")) else st
              val newChunks = effSt.audioChunks.drop(effSt.chunksDelivered)
              val newDelivered = effSt.audioChunks.size
              val done = (effSt.status == "complete" || effSt.status == "error") && newDelivered >= effSt.audioChunks.size
              val updated = effSt.copy(chunksDelivered = newDelivered, lastActivity = now)
              (queues.updated(args.queue_id, updated), Right((newChunks, done, updated.status, updated.errorMessage)))
        }.flatMap {
          case Left(err) =>
            IO.pure(CallToolResult(List(ContentBlock.Text(Json.obj("error" -> Json.fromString(err)).noSpaces))))
          case Right((chunks, done, status, errorMsg)) =>
            val res = PollAudioResult(chunks, done, status, errorMsg)
            IO.pure(CallToolResult(List(ContentBlock.Text(res.asJson.noSpaces))))
        }
      }.withVisibility(Visibility.App)

      McpServer(
        info = Implementation(name = "Say Demo", version = "1.0.0"),
        tools = Some(ToolSet.of(listVoices, say, createTtsQueue, addTtsText, endTtsQueue, cancelTtsQueue, pollTtsAudio)),
        resources = Some(
          UiResources.of[IO](
            UiResource(
              ResourceUri,
              "Say Demo View",
              html,
              Some("View HTML resource with CSP metadata for external dependencies."),
              UiResourceMeta(csp = Some(UiCsp(resourceDomains = Some(List("https://esm.sh", "https://unpkg.com")))))
            )
          )
        ),
        extensions = Some(Map(McpApps.serverExtension))
      )

  def defaultUri: Uri =
    sys.env.get("TTS_URL").flatMap(Uri.fromString(_).toOption).getOrElse(
      Uri.unsafeFromString("http://100.94.119.53:8768/v1")
    )

  def load: IO[McpServer[IO]] =
    for
      html   <- ViewPage.load("say-server/view")
      client <- EmberClientBuilder.default[IO].build.allocated.map(_._1)
      server <- apply(html, client, defaultUri)
    yield server

object Main extends ExampleMain("say-server", _ => SayServer.load)
