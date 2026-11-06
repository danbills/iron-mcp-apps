package ironmcp.extapps.shadertoy

import cats.effect.IO
import io.circe.{Codec, Decoder, Json, JsonObject}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{CallToolResult, ContentBlock, Implementation}
import ironmcp.schema.JsonSchemaOf
import org.scalajs.dom
import shadertoy.ShaderToyLite

import scala.scalajs.js

final case class NoArgs() derives Decoder, JsonSchemaOf

final case class ShaderInput(
    fragmentShader: String,
    common: Option[String] = None,
    bufferA: Option[String] = None,
    bufferB: Option[String] = None,
    bufferC: Option[String] = None,
    bufferD: Option[String] = None
) derives Codec.AsObject, JsonSchemaOf

final case class CompilationStatus(
    success: Boolean,
    errors: List[String],
    timestamp: Double
) derives Codec.AsObject

object ShadertoyView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "ShaderToy Renderer", version = "1.0.0")

  override def autoResize: Boolean = false

  private var currentDisplayMode: String = "inline"
  private var shaderToy: Option[ShaderToyLite] = None
  private var currentShaderSources: ShaderInput = ShaderInput("")
  private var lastCompilationStatus: CompilationStatus = CompilationStatus(success = true, errors = Nil, timestamp = js.Date.now())
  private var capturedErrors: List[String] = Nil

  private lazy val mainEl: dom.HTMLElement            = Dom.byId("main-el")
  private lazy val canvas: dom.HTMLCanvasElement      = Dom.byId("canvas")
  private lazy val fullscreenBtn: dom.HTMLButtonElement = Dom.byId("fullscreen-btn")

  private def resizeCanvas(): Unit =
    canvas.width = dom.window.innerWidth.toInt
    canvas.height = dom.window.innerHeight.toInt

  private def setupConsoleErrorHook(): Unit =
    val consoleDyn = dom.console.asInstanceOf[js.Dynamic]
    val origError = consoleDyn.error.bind(dom.console).asInstanceOf[js.Function1[js.Array[js.Any], Unit]]
    val hook: js.Function = (args: js.Array[js.Any]) => {
      origError(args)
      val msg = args.map(a => if a == null then "null" else a.toString).mkString(" ")
      if msg.contains("Shader compilation failed") || msg.contains("Program initialization failed") || msg.contains("Failed to compile") then
        capturedErrors = capturedErrors :+ msg
    }
    consoleDyn.error = hook

  private def compileAndUpdateStatus(input: ShaderInput, view: Option[McpAppView[IO]]): Unit =
    capturedErrors = Nil

    val st = shaderToy.getOrElse {
      val created = new ShaderToyLite("canvas")
      shaderToy = Some(created)
      created
    }

    st.setCommon(input.common.getOrElse(""))

    input.bufferA match
      case Some(bA) =>
        val cfg = js.Dynamic.literal(source = bA, iChannel0 = "A")
        st.setBufferA(cfg.asInstanceOf[js.Object])
      case None =>
        st.setBufferA(js.undefined)

    input.bufferB match
      case Some(bB) =>
        val cfg = js.Dynamic.literal(source = bB, iChannel1 = "B")
        st.setBufferB(cfg.asInstanceOf[js.Object])
      case None =>
        st.setBufferB(js.undefined)

    input.bufferC match
      case Some(bC) =>
        val cfg = js.Dynamic.literal(source = bC, iChannel2 = "C")
        st.setBufferC(cfg.asInstanceOf[js.Object])
      case None =>
        st.setBufferC(js.undefined)

    input.bufferD match
      case Some(bD) =>
        val cfg = js.Dynamic.literal(source = bD, iChannel3 = "D")
        st.setBufferD(cfg.asInstanceOf[js.Object])
      case None =>
        st.setBufferD(js.undefined)

    val imageCfg = js.Dynamic.literal(
      source = input.fragmentShader,
      iChannel0 = input.bufferA.fold[js.Any](js.undefined)(_ => "A"),
      iChannel1 = input.bufferB.fold[js.Any](js.undefined)(_ => "B"),
      iChannel2 = input.bufferC.fold[js.Any](js.undefined)(_ => "C"),
      iChannel3 = input.bufferD.fold[js.Any](js.undefined)(_ => "D")
    )
    st.setImage(imageCfg.asInstanceOf[js.Object])
    st.play()

    val hasErrors = capturedErrors.nonEmpty
    lastCompilationStatus = CompilationStatus(
      success = !hasErrors,
      errors = capturedErrors,
      timestamp = js.Date.now()
    )
    currentShaderSources = input

    if hasErrors then
      view.foreach { v =>
        if v.hostCapabilities.updateModelContext.isDefined then
          val errBlock = ContentBlock.Text(s"Shader compilation failed:\n${capturedErrors.mkString("\n")}")
          val structured = JsonObject("compilationStatus" -> lastCompilationStatus.asJson)
          val _ = v.updateModelContext(List(errBlock), Some(structured)).unsafeRunAndForget()(using cats.effect.unsafe.implicits.global)
      }

  private def toggleFullscreen(view: McpAppView[IO]): IO[Unit] =
    val newMode = if currentDisplayMode == "fullscreen" then DisplayMode.`inline` else DisplayMode.fullscreen
    view.requestDisplayMode(newMode)
      .map { chosen =>
        currentDisplayMode = chosen.asJson.asString.getOrElse("inline")
        if currentDisplayMode == "fullscreen" then mainEl.classList.add("fullscreen")
        else mainEl.classList.remove("fullscreen")
      }
      .handleErrorWith(_ => IO.unit)

  override def tools: IO[Option[ViewToolProvider[IO]]] =
    ViewTools.of[IO](
      ViewTool[ShaderInput](
        "set-shader-source",
        "Update the shader source code. Compiles and runs the new shader immediately.",
        title = Some("Set Shader Source")
      ) { args =>
        IO {
          compileAndUpdateStatus(args, None)
          val resultText =
            if lastCompilationStatus.success then "Shader compiled and running successfully."
            else s"Shader compilation failed:\n${lastCompilationStatus.errors.mkString("\n")}"
          val structured = Json.obj(
            "success"   -> Json.fromBoolean(lastCompilationStatus.success),
            "errors"    -> lastCompilationStatus.errors.asJson,
            "timestamp" -> Json.fromDoubleOrNull(lastCompilationStatus.timestamp)
          )
          CallToolResult.structured(resultText, structured)
        }
      },
      ViewTool[NoArgs](
        "get-shader-info",
        "Get the current shader source code and compilation status.",
        title = Some("Get Shader Info")
      ) { _ =>
        IO {
          val hasShader = currentShaderSources.fragmentShader.nonEmpty
          val isPlaying = shaderToy.exists(_.isPlaying())
          val statusText =
            if !hasShader then "No shader loaded."
            else if lastCompilationStatus.success then s"Shader is ${if isPlaying then "running" else "paused"}."
            else s"Shader has compilation errors:\n${lastCompilationStatus.errors.mkString("\n")}"

          val fullText = s"$statusText\n\nCurrent fragment shader:\n${if hasShader then currentShaderSources.fragmentShader else "(none)"}"
          val structured = Json.obj(
            "sources"           -> currentShaderSources.asJson,
            "compilationStatus" -> lastCompilationStatus.asJson,
            "isPlaying"         -> Json.fromBoolean(isPlaying)
          )
          CallToolResult.structured(fullText, structured)
        }
      }
    ).map(Some(_))

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    ui.onTeardown(IO {
      shaderToy.foreach(_.pause())
    }) >>
      view.hostContext.get.flatMap { ctx =>
        IO {
          setupConsoleErrorHook()
          resizeCanvas()
          dom.window.addEventListener("resize", (_: dom.Event) => resizeCanvas())

          if ctx.availableDisplayModes.exists(_.contains(DisplayMode.fullscreen)) then
            fullscreenBtn.classList.add("available")
          currentDisplayMode = ctx.displayMode.map(_.asJson.asString.getOrElse("inline")).getOrElse("inline")
          if currentDisplayMode == "fullscreen" then mainEl.classList.add("fullscreen")

          ui.onClick(fullscreenBtn)(toggleFullscreen(view))

          dom.document.addEventListener("keydown", (e: dom.KeyboardEvent) => {
            if e.key == "Escape" && currentDisplayMode == "fullscreen" then
              val _ = toggleFullscreen(view).unsafeRunAndForget()(using cats.effect.unsafe.implicits.global)
          })

          // Visibility observer
          val observer = new dom.IntersectionObserver((entries, _) => {
            entries.foreach { entry =>
              val isIntersecting = entry.asInstanceOf[js.Dynamic].isIntersecting.asInstanceOf[Boolean]
              if isIntersecting then shaderToy.foreach(_.play())
              else shaderToy.foreach(_.pause())
            }
          })
          observer.observe(mainEl)
        }
      } >>
      view.events.evalMap {
        case HostEvent.ToolInput(arguments) =>
          IO {
            Json.fromJsonObject(arguments).as[ShaderInput] match
              case Right(input) =>
                compileAndUpdateStatus(input, Some(view))
              case Left(err) =>
                dom.console.error("[APP] Invalid tool input:", err.getMessage)
          }

        case HostEvent.ToolInputPartial(_) =>
          IO.unit

        case HostEvent.ToolResult(_) =>
          IO.unit

        case HostEvent.ToolCancelled(_) =>
          IO.unit

        case HostEvent.Unreadable(_, _, _) =>
          IO.unit
      }.compile.drain
