package ironmcp.extapps.video

import cats.effect.{IO, Ref}
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.*
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** Upstream's video player: the tool result names a `videos://` resource,
  * read through the host as a base64 blob and played from a Blob URL; the
  * picker lists the server's videos (`resources/list`).
  */
object VideoView extends ExampleView:

  val appInfo: Implementation      = Implementation(name = "Video Resource Player", version = "1.0.0")
  override def theming: Boolean    = false

  private def el[A <: dom.Element](id: String): A = Dom.byId[A](id)
  private def loading      = el[dom.HTMLElement]("loading")
  private def loadingText  = el[dom.HTMLElement]("loading-text")
  private def error        = el[dom.HTMLElement]("error")
  private def errorMessage = el[dom.HTMLElement]("error-message")
  private def picker       = el[dom.HTMLElement]("video-picker-container")
  private def videoPicker  = el[dom.HTMLSelectElement]("video-picker")
  private def loadButton   = el[dom.HTMLButtonElement]("load-video-btn")
  private def player       = el[dom.HTMLElement]("player")
  private def video        = el[dom.HTMLVideoElement]("video")
  private def videoInfo    = el[dom.HTMLElement]("video-info")
  private def changeButton = el[dom.HTMLButtonElement]("change-video-btn")

  private def show(loadingOn: Boolean, errorOn: Boolean, pickerOn: Boolean, playerOn: Boolean): Unit =
    loading.style.display = if loadingOn then "flex" else "none"
    error.style.display = if errorOn then "block" else "none"
    picker.style.display = if pickerOn then "block" else "none"
    player.style.display = if playerOn then "block" else "none"

  private def showLoading(text: String): IO[Unit] = IO { loadingText.textContent = text; show(true, false, false, false) }
  private def showError(message: String): IO[Unit] = IO { errorMessage.textContent = message; show(false, true, false, false) }

  private def hasVideo: Boolean = video.src.nonEmpty && video.src != dom.window.location.href

  private def showPicker(keepPlayer: Boolean): IO[Unit] = IO {
    val keep = keepPlayer && hasVideo
    show(false, false, true, keep)
    changeButton.style.display = "none"
  }

  private def message(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    Ref.of[IO, Option[String]](None).flatMap { objectUrl =>

      def showPlayer(url: String, info: String): IO[Unit] =
        objectUrl.getAndSet(Some(url)).flatMap(previous =>
          IO {
            previous.foreach(dom.URL.revokeObjectURL)
            video.src = url
            videoInfo.textContent = info
            show(false, false, false, true)
            changeButton.style.display = "inline-block"
          }
        )

      def fetchAndPlay(uri: String, label: String): IO[Unit] =
        IO(dom.console.info("Requesting resource:", uri)) >>
          view.readServerResource(uri).flatMap { result =>
            result.contents.headOption match
              case Some(ResourceContents.Blob(_, blob, mimeType, _)) =>
                IO(dom.console.info("Resource received, blob size:", blob.length)) >>
                  IO {
                    // atob and a typed array: 200 MB of base64 never goes through Scala strings.
                    val binary = dom.window.atob(blob)
                    val bytes  = new Uint8Array(binary.length)
                    var i      = 0
                    while i < binary.length do
                      bytes(i) = binary.charAt(i).toShort
                      i += 1
                    val options = new dom.BlobPropertyBag { `type` = mimeType.fold("video/mp4")(m => m) }
                    dom.URL.createObjectURL(new dom.Blob(js.Array(bytes), options))
                  }.flatMap(showPlayer(_, label))
              case _ => IO.raiseError(new IllegalStateException("Resource response did not contain blob data"))
          }

      val discover: IO[Unit] =
        val had = hasVideo
        (if had then IO.unit else showLoading("Discovering available videos...")) >>
          view.listServerResources().attempt.flatMap {
            case Left(e) => IO(dom.console.error("Error discovering videos:", e.toString)) >> showError(message(e))
            case Right(listed) =>
              val videos = listed.resources.filter(r => (r.uri: String).startsWith("videos://"))
              if videos.isEmpty then showError("No videos available. Server has no video resources.")
              else
                IO {
                  videoPicker.innerHTML = """<option value="">Select a video...</option>"""
                  videos.foreach { r =>
                    val option = dom.document.createElement("option").asInstanceOf[dom.HTMLOptionElement]
                    option.value = r.uri
                    option.text = (r.name: String)
                    videoPicker.appendChild(option)
                  }
                } >> showPicker(had)
          }

      val loadSelected: IO[Unit] =
        val uri = videoPicker.value
        if uri.isEmpty then IO.unit
        else
          val label = videoPicker.options(videoPicker.selectedIndex).text
          showLoading("Loading video...") >>
            fetchAndPlay(uri, s"$label (loaded via MCP resource)").handleErrorWith(e =>
              IO(dom.console.error("Error loading video:", e.toString)) >> showError(message(e))
            )

      IO {
        ui.on(videoPicker, "change")(_ => IO(loadButton.disabled = videoPicker.value.isEmpty))
        ui.onClick(loadButton)(loadSelected)
        ui.onClick(changeButton)(discover)
      } >>
        SafeArea.follow(view, Dom.query[dom.HTMLElement](".main")).surround(
          view.events
            .evalMap {
              case HostEvent.ToolResult(result) =>
                val parsed =
                  for
                    s    <- result.structuredContent
                    uri  <- s.hcursor.get[String]("videoUri").toOption
                    desc <- s.hcursor.get[String]("description").toOption
                  yield (uri, desc)
                parsed match
                  case None => showError("Invalid tool result - could not parse video URI")
                  case Some((uri, desc)) =>
                    showLoading("Fetching video from MCP resource...") >>
                      fetchAndPlay(uri, s"Loaded via MCP resource ($desc)").handleErrorWith(e =>
                        IO(dom.console.error("Error fetching resource:", e.toString)) >> showError(message(e))
                      )
              case HostEvent.Unreadable(_, _, why) => showError(why)
              case _                               => IO.unit
            }
            .compile
            .drain
        )
    }
