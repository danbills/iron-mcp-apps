package ironmcp.extapps.say

import cats.effect.IO
import io.circe.{Decoder, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{ContentBlock, Implementation}
import org.scalajs.dom

import scala.concurrent.duration.*
import scala.scalajs.js

final case class AudioChunk(
    index: Int,
    audio_base64: String,
    char_start: Int,
    char_end: Int,
    duration_ms: Double
) derives Decoder

final case class PollAudioResponse(
    chunks: List[AudioChunk],
    done: Boolean,
    status: String,
    error: Option[String] = None
) derives Decoder

final case class CreateQueueResponse(
    queue_id: String,
    sample_rate: Option[Int] = None,
    error: Option[String] = None
) derives Decoder

final case class ChunkTiming(
    charStart: Int,
    charEnd: Int,
    audioStartTime: Double,
    audioEndTime: Double
)

object SayView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Say View", version = "1.0.0")

  private val SpeakLockKey = "mcp-tts-playing"

  private var displayText: String                  = ""
  private var charPosition: Int                    = 0
  private var status: String                       = "idle" // idle | playing | paused | finished
  private var displayMode: String                  = "inline"
  private var showInfo: Boolean                    = false

  private var currentVoice: String                 = "cosette"
  private var viewUuid: Option[String]             = None
  private var speakLockInterval: Option[Int]       = None
  private var queueId: Option[String]              = None
  private var audioContext: Option[dom.AudioContext] = None
  private var sampleRate: Int                      = 24000
  private var nextPlayTime: Double                 = 0.0
  private var playbackStartTime: Double            = 0.0
  private var chunkTimings: List[ChunkTiming]      = Nil
  private var pendingChunks: List[AudioChunk]      = Nil
  private var allAudioReceived: Boolean            = false
  private var isPolling: Boolean                   = false
  private var lastText: String                     = ""
  private var fullText: String                     = ""
  private var progressInterval: Option[Int]        = None
  private var lastModelContextUpdate: Double       = 0.0
  private var pendingModelTimeout: Option[Int]     = None
  private var audioOpInProgress: Boolean           = false

  private lazy val mainContainer: dom.HTMLElement     = Dom.byId("main-container")
  private lazy val textSpoken: dom.HTMLElement        = Dom.byId("text-spoken")
  private lazy val textPending: dom.HTMLElement       = Dom.byId("text-pending")
  private lazy val playPauseBtn: dom.HTMLButtonElement = Dom.byId("play-pause-btn")
  private lazy val restartBtn: dom.HTMLButtonElement   = Dom.byId("restart-btn")
  private lazy val fullscreenBtn: dom.HTMLButtonElement = Dom.byId("fullscreen-btn")
  private lazy val infoBtn: dom.HTMLButtonElement     = Dom.byId("info-btn")
  private lazy val infoPopup: dom.HTMLElement         = Dom.byId("info-popup")
  private lazy val textDisplay: dom.HTMLElement       = Dom.byId("text-display")

  private lazy val iconPlay: dom.HTMLElement         = Dom.byId("icon-play")
  private lazy val iconPause: dom.HTMLElement        = Dom.byId("icon-pause")
  private lazy val iconRestart: dom.HTMLElement      = Dom.byId("icon-restart-state")

  private lazy val linkRepo: dom.HTMLElement         = Dom.byId("link-repo")
  private lazy val linkModel: dom.HTMLElement        = Dom.byId("link-model")
  private lazy val linkVoices: dom.HTMLElement       = Dom.byId("link-voices")

  private def announcePlayback(): Unit =
    viewUuid.foreach { uuid =>
      val data = js.Dynamic.literal(uuid = uuid, timestamp = js.Date.now())
      dom.window.localStorage.setItem(SpeakLockKey, js.JSON.stringify(data))
    }

  private def clearSpeakLock(): Unit =
    viewUuid.foreach { uuid =>
      try
        val item = dom.window.localStorage.getItem(SpeakLockKey)
        if item != null then
          val parsed = js.JSON.parse(item)
          if parsed.uuid.asInstanceOf[String] == uuid then
            dom.window.localStorage.removeItem(SpeakLockKey)
      catch case _: Throwable => ()
    }

  private def startSpeakLockPolling(): Unit =
    stopSpeakLockPolling()
    speakLockInterval = Some(dom.window.setInterval(() => {
      try
        val item = dom.window.localStorage.getItem(SpeakLockKey)
        if item != null then
          val parsed = js.JSON.parse(item)
          val lockUuid = parsed.uuid.asInstanceOf[String]
          if !viewUuid.contains(lockUuid) then
            dom.console.log("[TTS] Another view started playing, pausing")
            pauseAudio()
      catch case _: Throwable => ()
    }, 200.0))

  private def stopSpeakLockPolling(): Unit =
    speakLockInterval.foreach(dom.window.clearInterval)
    speakLockInterval = None

  private def roundToWordEnd(pos: Int): Int =
    val text = lastText
    if pos >= text.length then text.length
    else if pos <= 0 then 0
    else if text.charAt(pos) == ' ' || text.charAt(pos) == '\n' then pos
    else
      var end = pos
      while end < text.length && text.charAt(end) != ' ' && text.charAt(end) != '\n' do
        end += 1
      end

  private def getCharacterPosition(currentTime: Double): Int =
    var rawPos = 0
    if chunkTimings.isEmpty then
      rawPos = js.Math.floor((currentTime - playbackStartTime) * 12.0).toInt
    else
      val optChunk = chunkTimings.find(c => currentTime >= c.audioStartTime && currentTime < c.audioEndTime)
      optChunk match
        case Some(chunk) =>
          val duration = chunk.audioEndTime - chunk.audioStartTime
          if duration <= 0.0 then rawPos = chunk.charStart
          else
            val progress = (currentTime - chunk.audioStartTime) / duration
            rawPos = js.Math.floor(chunk.charStart + progress * (chunk.charEnd - chunk.charStart)).toInt
        case None =>
          if chunkTimings.headOption.exists(c => currentTime < c.audioStartTime) then
            rawPos = 0
          else
            chunkTimings.lastOption.foreach { last =>
              if currentTime >= last.audioEndTime then rawPos = last.charEnd
            }
    roundToWordEnd(rawPos)

  private def updateDisplayText(): Unit =
    val spoken  = if charPosition <= displayText.length then displayText.substring(0, charPosition) else displayText
    val pending = if charPosition <= displayText.length then displayText.substring(charPosition) else ""
    textSpoken.textContent = spoken
    textPending.textContent = pending

  private def updateUiState(): Unit =
    iconPlay.style.display = if status == "idle" || status == "paused" then "block" else "none"
    iconPause.style.display = if status == "playing" then "block" else "none"
    iconRestart.style.display = if status == "finished" then "block" else "none"

  private def finishPlayback(): Unit =
    status = "finished"
    progressInterval.foreach(dom.window.clearInterval)
    progressInterval = None
    charPosition = lastText.length
    updateDisplayText()
    updateUiState()
    stopSpeakLockPolling()
    clearSpeakLock()

  private def startProgressTracking(): Unit =
    if progressInterval.isEmpty then
      progressInterval = Some(dom.window.setInterval(() => {
        audioContext.foreach { ctx =>
          charPosition = getCharacterPosition(ctx.currentTime)
          updateDisplayText()
          if allAudioReceived && ctx.currentTime >= nextPlayTime - 0.05 then
            finishPlayback()
        }
      }, 50.0))

  private def scheduleAudioChunkInternal(chunk: AudioChunk): Unit =
    audioContext.foreach { ctx =>
      val binaryStr = dom.window.atob(chunk.audio_base64)
      val bytes     = new js.typedarray.Uint8Array(binaryStr.length)
      var i = 0
      while i < binaryStr.length do
        bytes(i) = binaryStr.charAt(i).toShort
        i += 1
      val int16Array = new js.typedarray.Int16Array(bytes.buffer)
      val float32Array = new js.typedarray.Float32Array(int16Array.length)
      var j = 0
      while j < int16Array.length do
        float32Array(j) = (int16Array(j).toDouble / 32768.0).toFloat
        j += 1

      val buffer = ctx.createBuffer(1, float32Array.length, sampleRate)
      buffer.getChannelData(0).set(float32Array)
      val source = ctx.createBufferSource()
      source.buffer = buffer
      val _ = source.connect(ctx.destination)

      val startTime = js.Math.max(ctx.currentTime, nextPlayTime)
      val duration  = buffer.duration
      if chunkTimings.isEmpty then
        playbackStartTime = startTime
        status = "playing"
        updateUiState()
        announcePlayback()
        startSpeakLockPolling()
        startProgressTracking()

      source.start(startTime)
      nextPlayTime = startTime + duration
      chunkTimings = chunkTimings :+ ChunkTiming(chunk.char_start, chunk.char_end, startTime, nextPlayTime)

      val thisBufferEndTime = nextPlayTime
      source.onended = _ => {
        audioContext.foreach { c =>
          val ct = c.currentTime
          if allAudioReceived && thisBufferEndTime >= nextPlayTime - 0.01 && ct >= nextPlayTime - 0.05 then
            finishPlayback()
        }
      }
    }

  private def scheduleAudioChunk(chunk: AudioChunk): Unit =
    audioContext match
      case Some(ctx) if ctx.state == "suspended" && chunkTimings.isEmpty =>
        pendingChunks = pendingChunks :+ chunk
      case Some(_) =>
        scheduleAudioChunkInternal(chunk)
      case None =>
        pendingChunks = pendingChunks :+ chunk

  private def pollAudioLoop(view: McpAppView[IO]): IO[Unit] =
    def loop(emptyPollCount: Int): IO[Unit] =
      queueId match
        case None => IO.unit
        case Some(qid) =>
          view.callServerTool("poll_tts_audio", JsonObject("queue_id" -> qid.asJson))
            .attempt
            .flatMap {
              case Left(err) =>
                IO(dom.console.log("[TTS] Polling error:", err.toString))
              case Right(result) =>
                val rawText = result.content.headOption match
                  case Some(ContentBlock.Text(t, _, _)) => t
                  case _                                => ""
                io.circe.parser.decode[PollAudioResponse](rawText) match
                  case Left(err) =>
                    IO(dom.console.log("[TTS] Polling json decode error:", err.getMessage))
                  case Right(data) =>
                    if data.error.nonEmpty then
                      IO(dom.console.log("[TTS] Queue error:", data.error.getOrElse("")))
                    else
                      IO {
                        data.chunks.foreach(scheduleAudioChunk)
                        if data.done then allAudioReceived = true
                      } >> {
                        if data.done then IO.unit
                        else if data.chunks.nonEmpty then
                          IO.sleep(20.millis) >> loop(0)
                        else
                          val nextCount = emptyPollCount + 1
                          val delayMs   = js.Math.min(50 + (nextCount * 50), 150)
                          IO.sleep(delayMs.millis) >> loop(nextCount)
                      }
            }
    if isPolling then IO.unit
    else
      IO { isPolling = true } >> loop(0).guarantee(IO { isPolling = false })

  private def cancelCurrentQueue(view: McpAppView[IO]): IO[Unit] =
    queueId match
      case Some(qid) =>
        view.callServerTool("cancel_tts_queue", JsonObject("queue_id" -> qid.asJson)).attempt.void
      case None => IO.unit

  private def initTTSQueue(view: McpAppView[IO]): IO[Boolean] =
    if queueId.nonEmpty then IO.pure(true)
    else
      IO {
        progressInterval.foreach(dom.window.clearInterval)
        progressInterval = None
        chunkTimings = Nil
        pendingChunks = Nil
        allAudioReceived = false
        charPosition = 0
        status = "idle"
        updateUiState()
        audioContext.foreach(ctx => { val _ = ctx.close() })
        audioContext = None
      } >>
        view.callServerTool("create_tts_queue", JsonObject("voice" -> currentVoice.asJson))
          .attempt
          .flatMap {
            case Left(err) =>
              IO(dom.console.error("[TTS] initTTSQueue error:", err.toString)).as(false)
            case Right(result) =>
              val rawText = result.content.headOption match
                case Some(ContentBlock.Text(t, _, _)) => t
                case _                                => ""
              io.circe.parser.decode[CreateQueueResponse](rawText) match
                case Left(err) =>
                  IO(dom.console.error("[TTS] Failed to parse create_tts_queue result:", err.getMessage)).as(false)
                case Right(data) =>
                  if data.error.nonEmpty then
                    IO(dom.console.log("[TTS] Queue creation error:", data.error.getOrElse(""))).as(false)
                  else
                    IO {
                      queueId = Some(data.queue_id)
                      sampleRate = data.sample_rate.getOrElse(24000)
                      audioContext = Some(new dom.AudioContext())
                      nextPlayTime = 0.0
                    } >> pollAudioLoop(view).start.as(true)
          }

  private def sendTextToTTS(view: McpAppView[IO], text: String): IO[Unit] =
    queueId match
      case Some(qid) if text.length > lastText.length =>
        val diff = text.substring(lastText.length)
        lastText = text
        view.callServerTool("add_tts_text", JsonObject("queue_id" -> qid.asJson, "text" -> diff.asJson)).attempt.void
      case _ => IO.unit

  private def ensureAudioContextResumed(): IO[Unit] =
    audioContext match
      case Some(ctx) if ctx.state == "suspended" =>
        IO.fromPromise(IO(ctx.resume())).flatMap { _ =>
          IO {
            if pendingChunks.nonEmpty then
              nextPlayTime = ctx.currentTime
              val chunks = pendingChunks
              pendingChunks = Nil
              chunks.foreach(scheduleAudioChunkInternal)
          }
        }
      case _ => IO.unit

  private def pauseAudio(): Unit =
    audioContext.foreach { ctx =>
      if ctx.state == "running" then
        val _ = ctx.suspend()
        status = "paused"
        updateUiState()
        stopSpeakLockPolling()
        clearSpeakLock()
    }

  private def restartPlayback(view: McpAppView[IO]): IO[Unit] =
    if audioOpInProgress then IO.unit
    else
      IO { audioOpInProgress = true } >>
        (for
          _ <- IO {
            progressInterval.foreach(dom.window.clearInterval)
            progressInterval = None
          }
          _ <- cancelCurrentQueue(view)
          _ <- IO {
            audioContext.foreach(ctx => { val _ = ctx.close() })
            audioContext = None
          }
          textToReplay = if fullText.nonEmpty then fullText else lastText
          _ <- if textToReplay.isEmpty then IO.unit
               else
                 IO {
                   queueId = None
                   lastText = ""
                   nextPlayTime = 0.0
                   playbackStartTime = 0.0
                   status = "idle"
                   chunkTimings = Nil
                   allAudioReceived = false
                   charPosition = 0
                   pendingChunks = Nil
                   displayText = textToReplay
                   updateDisplayText()
                   updateUiState()
                 } >>
                   initTTSQueue(view).flatMap { ok =>
                     if !ok then IO.unit
                     else
                       sendTextToTTS(view, textToReplay) >>
                         queueId.fold(IO.unit)(qid =>
                           view.callServerTool("end_tts_queue", JsonObject("queue_id" -> qid.asJson)).attempt.void
                         )
                   }
        yield ()).guarantee(IO { audioOpInProgress = false })

  private def togglePlayPause(view: McpAppView[IO]): IO[Unit] =
    if audioOpInProgress then IO.unit
    else if status == "finished" then restartPlayback(view)
    else
      audioContext match
        case None => IO.unit
        case Some(ctx) =>
          if ctx.state == "suspended" || pendingChunks.nonEmpty then
            ensureAudioContextResumed() >> IO {
              status = "playing"
              updateUiState()
              announcePlayback()
              startSpeakLockPolling()
            }
          else if status == "paused" then
            IO.fromPromise(IO(ctx.resume())) >> IO {
              status = "playing"
              updateUiState()
              announcePlayback()
              startSpeakLockPolling()
            }
          else if status == "playing" then
            IO.fromPromise(IO(ctx.suspend())) >> IO {
              status = "paused"
              updateUiState()
              stopSpeakLockPolling()
              clearSpeakLock()
            }
          else IO.unit

  private def toggleFullscreen(view: McpAppView[IO]): IO[Unit] =
    val target = if displayMode == "fullscreen" then DisplayMode.`inline` else DisplayMode.fullscreen
    view.requestDisplayMode(target)
      .map { chosen =>
        displayMode = chosen.asJson.asString.getOrElse("inline")
        if displayMode == "fullscreen" then mainContainer.classList.add("fullscreen")
        else mainContainer.classList.remove("fullscreen")
      }
      .handleErrorWith(_ => IO.unit)

  private def updateModelContext(view: McpAppView[IO]): IO[Unit] =
    if view.hostCapabilities.updateModelContext.isEmpty || displayText.isEmpty || status == "idle" then IO.unit
    else
      val now = js.Date.now()
      val timeSince = now - lastModelContextUpdate
      val debounceMs = 2000.0

      val doUpdate = IO {
        lastModelContextUpdate = js.Date.now()
        pendingModelTimeout = None
        val snippetStart = js.Math.max(0, charPosition - 30).toInt
        val snippetEnd   = js.Math.min(displayText.length, charPosition + 10).toInt
        val snippet = "..." + displayText.substring(snippetStart, charPosition) + "█" + displayText.substring(charPosition, snippetEnd) + "..."
        val statusText =
          if status == "finished" then s"Finished playing ${displayText.length} chars."
          else if status == "paused" then s"PAUSED at \"$snippet\" ($charPosition/${displayText.length})"
          else s"Playing: \"$snippet\" ($charPosition/${displayText.length})"
        statusText
      }.flatMap { statusText =>
        view.updateModelContext(List(ContentBlock.Text(statusText))).handleErrorWith(_ => IO.unit)
      }

      IO {
        pendingModelTimeout.foreach(dom.window.clearTimeout)
        pendingModelTimeout = None
      } >> {
        if timeSince >= debounceMs then doUpdate
        else
          IO {
            val remaining = (debounceMs - timeSince).toInt
            pendingModelTimeout = Some(dom.window.setTimeout(() => {
              val _ = doUpdate.unsafeRunAndForget()(using cats.effect.unsafe.implicits.global)
            }, remaining.toDouble))
          }
      }

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    ui.onTeardown(
      IO {
        progressInterval.foreach(dom.window.clearInterval)
        progressInterval = None
        stopSpeakLockPolling()
        clearSpeakLock()
      } >> cancelCurrentQueue(view) >> IO {
        audioContext.foreach(ctx => { val _ = ctx.close() })
        audioContext = None
      }
    ) >>
      view.hostContext.get.flatMap { ctx =>
        IO {
          if ctx.availableDisplayModes.exists(_.contains(DisplayMode.fullscreen)) then
            fullscreenBtn.classList.add("available")
          displayMode = ctx.displayMode.map(_.asJson.asString.getOrElse("inline")).getOrElse("inline")
          if displayMode == "fullscreen" then mainContainer.classList.add("fullscreen")

          ui.onClick(textDisplay)(togglePlayPause(view))
          ui.onClick(playPauseBtn)(togglePlayPause(view))
          ui.onClick(restartBtn)(restartPlayback(view))
          ui.onClick(fullscreenBtn)(toggleFullscreen(view))

          ui.onClick(infoBtn)(IO {
            showInfo = !showInfo
            infoPopup.style.display = if showInfo then "block" else "none"
          })

          ui.onClick(linkRepo)(view.openLink("https://github.com/kyutai-labs/pocket-tts").attempt.void)
          ui.onClick(linkModel)(view.openLink("https://huggingface.co/kyutai/pocket-tts").attempt.void)
          ui.onClick(linkVoices)(view.openLink("https://huggingface.co/kyutai/tts-voices").attempt.void)

          mainContainer.addEventListener("keydown", (e: dom.KeyboardEvent) => {
            if e.key == "Escape" && displayMode == "fullscreen" then
              val _ = toggleFullscreen(view).unsafeRunAndForget()(using cats.effect.unsafe.implicits.global)
          })
        }
      } >>
      SafeArea.follow(view, mainContainer).surround(
        view.events.evalMap {
          case HostEvent.ToolInputPartial(arguments) =>
            val newText = arguments("text").flatMap(_.asString).getOrElse("")
            if newText.isEmpty then IO.unit
            else
              val isNewSession = lastText.nonEmpty && !newText.startsWith(lastText)
              IO {
                if isNewSession then
                  queueId = None
                  lastText = ""
                displayText = newText
                updateDisplayText()
              } >> initTTSQueue(view).flatMap { ok =>
                if ok then sendTextToTTS(view, newText) >> updateModelContext(view)
                else IO.unit
              }

          case HostEvent.ToolInput(arguments) =>
            val text = arguments("text").flatMap(_.asString).getOrElse("")
            val voice = arguments("voice").flatMap(_.asString).getOrElse("cosette")
            currentVoice = voice
            if text.isEmpty then IO.unit
            else
              val isNewSession = lastText.nonEmpty && !text.startsWith(lastText)
              IO {
                if isNewSession then
                  queueId = None
                  lastText = ""
                displayText = text
                updateDisplayText()
              } >> initTTSQueue(view).flatMap { ok =>
                if ok then sendTextToTTS(view, text) >> updateModelContext(view)
                else IO.unit
              }

          case HostEvent.ToolResult(callResult) =>
            fullText = lastText
            val resultUuid = callResult.content.headOption.flatMap {
              case ContentBlock.Text(_, _, Some(meta)) => meta.find(_._1.contains("viewUUID")).flatMap(_._2.asString)
              case _                                   => None
            }
            resultUuid.foreach(u => viewUuid = Some(u))
            queueId match
              case Some(qid) =>
                view.callServerTool("end_tts_queue", JsonObject("queue_id" -> qid.asJson)).attempt.void
              case None => IO.unit

          case HostEvent.ToolCancelled(_) =>
            cancelCurrentQueue(view)

          case HostEvent.Unreadable(_, _, _) =>
            IO.unit
        }.compile.drain
      )
