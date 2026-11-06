package ironmcp.extapps.transcript

import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{ContentBlock, Implementation}
import org.scalajs.dom
import webspeech.{SpeechRecognition, SpeechRecognitionErrorEvent, SpeechRecognitionEvent}

import scala.scalajs.js

/** Upstream's transcript-server View: speech-to-text transcription using the
  * Web Speech API and mic audio levels via the Web Audio API. Transcribed text
  * can be sent to the host via sendMessage or mirrored in the model context.
  */
object TranscriptView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Live Transcript", version = "1.0.0")

  private var isListening: Boolean           = false
  private var lastSentIndex: Int             = 0
  private var timerStart: Option[Double]     = None
  private var timerInterval: Option[Int]     = None
  private var audioContext: Option[dom.AudioContext] = None
  private var micStream: Option[dom.MediaStream]     = None
  private var micAnalyser: Option[dom.AnalyserNode]  = None
  private var animationFrame: Option[Int]    = None
  private var recognition: Option[SpeechRecognition] = None
  private var committedResultCount: Int      = 0
  private var interimTranscript: String      = ""

  private lazy val levelBarEl: dom.HTMLElement       = Dom.byId("level-bar")
  private lazy val micLevelEl: dom.HTMLElement       = Dom.byId("mic-level")
  private lazy val timerEl: dom.HTMLElement          = Dom.byId("timer")
  private lazy val transcriptEl: dom.HTMLElement     = Dom.byId("transcript")
  private lazy val startBtn: dom.HTMLButtonElement   = Dom.byId("start-btn")
  private lazy val copyBtn: dom.HTMLButtonElement    = Dom.byId("copy-btn")
  private lazy val clearBtn: dom.HTMLButtonElement   = Dom.byId("clear-btn")
  private lazy val sendBtn: dom.HTMLButtonElement    = Dom.byId("send-btn")

  private val startSvg =
    """<svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg>Start"""

  private val stopSvg =
    """<svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>Stop"""

  private def startAudioCapture(): IO[Boolean] =
    IO.fromPromise(IO {
      val ctx = new dom.AudioContext()
      audioContext = Some(ctx)
      val constraints = js.Dynamic.literal(audio = true).asInstanceOf[dom.MediaStreamConstraints]
      dom.window.navigator.mediaDevices.getUserMedia(constraints)
    }).map { stream =>
      micStream = Some(stream)
      audioContext.foreach { ctx =>
        val source   = ctx.createMediaStreamSource(stream)
        val analyser = ctx.createAnalyser()
        analyser.fftSize = 256
        val _ = source.connect(analyser)
        micAnalyser = Some(analyser)
      }
      updateAudioLevels()
      dom.console.log("[Transcript] Audio capture started")
      true
    }.handleErrorWith { e =>
      IO {
        dom.console.error("[Transcript] Failed to start audio capture:", e.toString)
        false
      }
    }

  private def updateAudioLevels(): Unit =
    micAnalyser match
      case Some(analyser) if isListening =>
        val dataArray = new js.typedarray.Uint8Array(analyser.frequencyBinCount)
        analyser.getByteFrequencyData(dataArray)
        var sum = 0.0
        var i   = 0
        while i < dataArray.length do
          sum += dataArray(i).toDouble
          i += 1
        val average = if dataArray.length > 0 then sum / dataArray.length else 0.0
        val level   = js.Math.min(100.0, (average / 128.0) * 100.0)
        micLevelEl.style.width = s"$level%"
        animationFrame = Some(dom.window.requestAnimationFrame(_ => updateAudioLevels()))
      case _ =>
        micLevelEl.style.width = "0%"

  private def stopAudioCapture(): Unit =
    animationFrame.foreach(dom.window.cancelAnimationFrame)
    animationFrame = None
    micStream.foreach { stream =>
      val tracks = stream.getTracks()
      var i      = 0
      while i < tracks.length do
        tracks(i).stop()
        i += 1
    }
    micStream = None
    audioContext.foreach { ctx =>
      val _ = ctx.close()
    }
    audioContext = None
    micAnalyser = None
    micLevelEl.style.width = "0%"

  private def startSpeechRecognition(view: McpAppView[IO], ui: Ui): Boolean =
    SpeechRecognition() match
      case None =>
        dom.console.warn("[Transcript] Speech recognition not supported")
        false
      case Some(rec) =>
        recognition = Some(rec)
        rec.continuous = true
        rec.interimResults = true
        rec.lang = "en-US"

        rec.onstart = _ =>
          dom.console.log("[Transcript] Speech recognition started")
          committedResultCount = 0
          interimTranscript = ""

        rec.onresult = (event: SpeechRecognitionEvent) =>
          interimTranscript = ""
          val results = event.results
          var i       = 0
          while i < results.length do
            val result     = results(i)
            val transcript = result(0).transcript
            if result.isFinal then
              if i >= committedResultCount then
                val text = transcript.trim()
                if text.nonEmpty then
                  clearInterimTranscript()
                  addTranscriptEntry(text, isFinal = true)
                  updateSendButton()
                  ui.run(updateModelContext(view))
                committedResultCount = i + 1
            else interimTranscript += transcript
            i += 1

          if interimTranscript.trim().nonEmpty then
            updateInterimTranscript("", interimTranscript)
          else clearInterimTranscript()

        rec.onerror = (event: SpeechRecognitionErrorEvent) =>
          dom.console.error("[Transcript] Speech recognition error:", event.error)
          if event.error == "not-allowed" then
            addTranscriptEntry("Microphone access denied", isFinal = true)
            stopListening(view, ui)

        rec.onend = _ =>
          dom.console.log("[Transcript] Speech recognition ended")
          if isListening then
            try recognition.foreach(_.start())
            catch case _: Throwable => ()

        try
          rec.start()
          true
        catch
          case e: Throwable =>
            dom.console.error("[Transcript] Failed to start speech recognition:", e.toString)
            false

  private def commitFinalTranscript(view: McpAppView[IO], ui: Ui): Unit =
    val textToCommit = interimTranscript.trim()
    if textToCommit.nonEmpty then
      clearInterimTranscript()
      addTranscriptEntry(textToCommit, isFinal = true)
      updateSendButton()
      ui.run(updateModelContext(view))
    committedResultCount = 0
    interimTranscript = ""

  private def stopSpeechRecognition(view: McpAppView[IO], ui: Ui): Unit =
    commitFinalTranscript(view, ui)
    recognition.foreach { rec =>
      try rec.stop()
      catch case _: Throwable => ()
    }
    recognition = None

  private def formatTime(seconds: Int): String =
    val mins   = seconds / 60
    val secs   = seconds % 60
    val secStr = if secs < 10 then s"0$secs" else secs.toString
    s"$mins:$secStr"

  private def startTimer(): Unit =
    val start = js.Date.now()
    timerStart = Some(start)
    timerEl.textContent = "0:00"
    timerEl.classList.add("active")
    timerInterval = Some(
      dom.window.setInterval(
        () =>
          timerStart.foreach { s =>
            val elapsed = ((js.Date.now() - s) / 1000.0).toInt
            timerEl.textContent = formatTime(elapsed)
          },
        1000.0
      )
    )

  private def stopTimer(): Unit =
    timerInterval.foreach(dom.window.clearInterval)
    timerInterval = None
    timerStart = None
    timerEl.classList.remove("active")

  private def clearTranscriptPlaceholder(): Unit =
    Option(transcriptEl.querySelector(".transcript-placeholder")).foreach(_.remove())

  private def escapeHtml(text: String): String =
    val div = dom.document.createElement("div")
    div.textContent = text
    div.innerHTML

  private def addTranscriptEntry(text: String, isFinal: Boolean): Unit =
    if text.trim().nonEmpty then
      clearTranscriptPlaceholder()
      clearInterimTranscript()
      val timestamp = (new js.Date()).toLocaleTimeString()
      val entry     = dom.document.createElement("p").asInstanceOf[dom.HTMLElement]
      entry.className = if isFinal then "transcript-entry" else "transcript-entry interim"
      entry.innerHTML = s"""<div class="timestamp">$timestamp</div>${escapeHtml(text)}"""
      val _ = transcriptEl.appendChild(entry)

  private def updateInterimTranscript(finalText: String, interimText: String): Unit =
    clearTranscriptPlaceholder()
    val interim = Option(transcriptEl.querySelector(".transcript-entry.interim").asInstanceOf[dom.HTMLElement]).getOrElse {
      val el = dom.document.createElement("p").asInstanceOf[dom.HTMLElement]
      el.className = "transcript-entry interim"
      val _ = transcriptEl.appendChild(el)
      el
    }
    val timestamp   = (new js.Date()).toLocaleTimeString()
    val finalHtml   = escapeHtml(finalText)
    val interimHtml = if interimText.nonEmpty then s"""<span class="interim-delta">${escapeHtml(interimText)}</span>""" else ""
    interim.innerHTML = s"""<div class="timestamp">$timestamp</div>$finalHtml$interimHtml"""

  private def clearInterimTranscript(): Unit =
    Option(transcriptEl.querySelector(".transcript-entry.interim")).foreach(_.remove())

  private def formatEntry(entry: dom.HTMLElement): String =
    val timestamp = Option(entry.querySelector(".timestamp")).map(_.textContent.trim)
    val clone     = entry.cloneNode(true).asInstanceOf[dom.HTMLElement]
    Option(clone.querySelector(".timestamp")).foreach(_.remove())
    val text = Option(clone.textContent).map(_.trim).getOrElse("")
    if text.isEmpty then ""
    else timestamp.filter(_.nonEmpty).fold(text)(ts => s"[$ts] $text")

  private def formatEntries(entries: List[dom.HTMLElement]): String =
    entries.map(formatEntry).filter(_.nonEmpty).mkString("\n")

  private def getAllEntries(): List[dom.HTMLElement] =
    val nodes = transcriptEl.querySelectorAll(".transcript-entry:not(.interim)")
    (0 until nodes.length).map(i => nodes(i).asInstanceOf[dom.HTMLElement]).toList

  private def getUnsentEntries(): List[dom.HTMLElement] =
    getAllEntries().drop(lastSentIndex)

  private def getAllTranscriptText(): String =
    formatEntries(getAllEntries())

  private def getUnsentText(): String =
    formatEntries(getUnsentEntries())

  private def updateSendButton(): Unit =
    sendBtn.disabled = getUnsentEntries().isEmpty

  private def updateModelContext(view: McpAppView[IO]): IO[Unit] =
    if view.hostCapabilities.updateModelContext.isEmpty then IO.unit
    else
      val text        = getUnsentText()
      val unsentCount = getUnsentEntries().size
      dom.console.log(s"[Transcript] Updating model context: ${if text.nonEmpty then text else "(empty)"}")
      val status      = if isListening then "listening" else "paused"
      val frontmatter = s"---\ntool: transcribe\nstatus: $status\nunsent-entries: $unsentCount\n---"
      val markdown    = if text.nonEmpty then s"$frontmatter\n\n$text" else frontmatter
      view
        .updateModelContext(List(ContentBlock.Text(markdown)))
        .handleErrorWith(e => IO(dom.console.warn("[Transcript] Failed to update model context:", e.toString)))

  private def startListening(view: McpAppView[IO], ui: Ui): IO[Unit] =
    IO {
      isListening = true
      startBtn.innerHTML = stopSvg
      startBtn.classList.add("recording")
      levelBarEl.classList.add("active")
      startTimer()
    } >>
      startAudioCapture().flatMap { micOk =>
        if !micOk then
          IO {
            addTranscriptEntry("Microphone access denied", isFinal = true)
            stopListening(view, ui)
          }
        else if !startSpeechRecognition(view, ui) then
          IO {
            addTranscriptEntry("Speech recognition not available", isFinal = true)
            stopListening(view, ui)
          }
        else IO.unit
      }

  private def stopListening(view: McpAppView[IO], ui: Ui): Unit =
    isListening = false
    startBtn.innerHTML = startSvg
    startBtn.classList.remove("recording")
    levelBarEl.classList.remove("active")
    stopTimer()
    stopSpeechRecognition(view, ui)
    stopAudioCapture()

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    ui.onTeardown(IO(stopListening(view, ui))) >>
      IO {
        ui.onClick(startBtn) {
          if isListening then IO(stopListening(view, ui))
          else startListening(view, ui)
        }

        ui.onClick(copyBtn) {
          val text = getAllTranscriptText()
          if text.isEmpty then IO.unit
          else
            IO.fromPromise(IO(dom.window.navigator.clipboard.writeText(text)))
              .flatMap { _ =>
                IO {
                  copyBtn.classList.add("copied")
                  val _ = dom.window.setTimeout(() => copyBtn.classList.remove("copied"), 1000.0)
                  dom.console.log("[Transcript] Transcript copied to clipboard")
                }
              }
              .handleErrorWith(e => IO(dom.console.error("[Transcript] Failed to copy:", e.toString)))
        }

        ui.onClick(clearBtn) {
          IO {
            transcriptEl.innerHTML = """<p class="transcript-placeholder">Your speech will appear here...</p>"""
            lastSentIndex = 0
            updateSendButton()
          } >> updateModelContext(view)
        }

        ui.onClick(sendBtn) {
          val unsentEntries  = getUnsentEntries()
          val transcriptText = getUnsentText()
          if unsentEntries.isEmpty || transcriptText.isEmpty then IO.unit
          else
            IO(dom.console.log("[Transcript] Sending transcript:", transcriptText)) >>
              view
                .sendMessage(List(ContentBlock.Text(transcriptText)))
                .flatMap { accepted =>
                  if !accepted then IO(dom.console.warn("[Transcript] Message was rejected"))
                  else
                    IO {
                      dom.console.log("[Transcript] Message sent successfully")
                      unsentEntries.foreach(_.classList.add("sent"))
                      Option(transcriptEl.querySelector(".sent-divider")).foreach(_.remove())
                      val lastEntry = unsentEntries.last
                      val divider   = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
                      divider.className = "sent-divider"
                      val time = (new js.Date()).toLocaleTimeString()
                      divider.innerHTML = s"<span>sent $time</span>"
                      val _ = lastEntry.insertAdjacentElement("afterend", divider)
                      lastSentIndex = getAllEntries().size
                      updateSendButton()
                    } >> updateModelContext(view)
                }
                .handleErrorWith(e => IO(dom.console.error("[Transcript] Failed to send message:", e.toString)))
        }
      } >>
      SafeArea.follow(view, Dom.query[dom.HTMLElement](".transcript-app")).surround(
        view.events.compile.drain
      )
