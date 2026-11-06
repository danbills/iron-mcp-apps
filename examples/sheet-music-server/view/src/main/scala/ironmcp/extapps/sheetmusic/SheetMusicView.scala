package ironmcp.extapps.sheetmusic

import abcjs.{ABCJS, SynthController}
import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.Implementation
import org.scalajs.dom

import scala.scalajs.js

/** Upstream's sheet music View: abcjs renders the tool's ABC notation as it
  * arrives (the tool input; the server only checks it parses) and plays it.
  */
object SheetMusicView extends ExampleView:

  val appInfo: Implementation   = Implementation(name = "Sheet Music App", version = "1.0.0")
  override def theming: Boolean = false

  private def setStatus(text: String, error: Boolean = false): Unit =
    val status = Dom.byId[dom.HTMLElement]("status")
    status.textContent = text
    if error then status.classList.add("error") else status.classList.remove("error")

  private def render(abc: String): IO[Unit] =
    val sheet    = Dom.byId[dom.HTMLElement]("sheet-music")
    val controls = Dom.byId[dom.HTMLElement]("audio-controls")
    val rendered = IO {
      setStatus("Rendering...")
      sheet.innerHTML = ""
      controls.innerHTML = ""
      val tunes = ABCJS.renderAbc(sheet, abc, js.Dynamic.literal(responsive = "resize", add_classes = true))
      if tunes.isEmpty then throw new IllegalStateException("Failed to parse music notation")
      if !ABCJS.synth.supportsAudio() then throw new IllegalStateException("Audio not supported in this browser")
      val synth = SynthController()
      synth.load(controls, null, js.Dynamic.literal(displayLoop = true, displayPlay = true, displayProgress = true))
      (synth, tunes(0))
    }
    rendered
      .flatMap((synth, tune) => IO.fromPromise(IO(synth.setTune(tune, false, js.Dynamic.literal()))))
      .flatMap(_ => IO(setStatus("Ready to play!")))
      .handleErrorWith { e =>
        IO {
          dom.console.error("Render error:", e.toString)
          setStatus(s"Error: ${Option(e.getMessage).getOrElse(e.toString)}", error = true)
          controls.innerHTML = ""
        }
      }

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    IO {
      val navigator = dom.window.navigator.asInstanceOf[js.Dynamic]
      if !js.isUndefined(navigator.audioSession) then navigator.audioSession.`type` = "playback"
    } >>
      SafeArea.follow(view, Dom.query[dom.HTMLElement](".main")).surround(
        view.events
          .evalMap {
            case HostEvent.ToolInput(arguments) =>
              IO(dom.console.info("Received tool input:", io.circe.Json.fromJsonObject(arguments).noSpaces)) >>
                arguments("abcNotation").flatMap(_.asString).fold(IO(setStatus("No ABC notation provided", error = true)))(render)
            case _ => IO.unit
          }
          .compile
          .drain
      )
