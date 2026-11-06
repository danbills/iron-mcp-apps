package ironmcp.extapps.three

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
import three.Three

import scala.scalajs.js

final case class NoArgs() derives Decoder, JsonSchemaOf

final case class SetSceneSourceArgs(
    code: String,
    height: Option[Int] = None
) derives Codec.AsObject, JsonSchemaOf

final case class SceneResult(
    success: Boolean,
    error: Option[String] = None
) derives Codec.AsObject

final case class SceneInfo(
    code: Option[String],
    height: Int,
    error: Option[String],
    isRendering: Boolean
) derives Codec.AsObject

object ThreejsView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "Three.js View", version = "1.0.0")

  val DefaultCode: String =
    """const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(75, width / height, 0.1, 1000);
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
renderer.setSize(width, height);
renderer.setClearColor(0x000000, 0);

const cube = new THREE.Mesh(
  new THREE.BoxGeometry(1, 1, 1),
  new THREE.MeshStandardMaterial({ color: 0x00ff88 })
);
cube.rotation.x = 0.5;
cube.rotation.y = 0.7;
scene.add(cube);

const keyLight = new THREE.DirectionalLight(0xffffff, 1.2);
keyLight.position.set(1, 1, 2);
scene.add(keyLight);
const fillLight = new THREE.DirectionalLight(0x8888ff, 0.4);
fillLight.position.set(-1, 0, -1);
scene.add(fillLight);
scene.add(new THREE.AmbientLight(0x404040, 0.5));

camera.position.z = 3;

function animate() {
  requestAnimationFrame(animate);
  cube.rotation.x += 0.01;
  cube.rotation.y += 0.01;
  renderer.render(scene, camera);
}
animate();"""

  private var currentDisplayMode: String = "inline"
  private var currentCode: String        = DefaultCode
  private var currentHeight: Int         = 400
  private var lastError: Option[String]  = None
  private var isRendering: Boolean       = false
  private var rafIds: List[Int]          = Nil

  private lazy val container: dom.HTMLElement         = Dom.byId("container")
  private lazy val shimmer: dom.HTMLElement           = Dom.byId("shimmer")
  private lazy val shimmerCode: dom.HTMLElement       = Dom.byId("shimmer-code")
  private lazy val canvas: dom.HTMLCanvasElement      = Dom.byId("threejs-canvas")
  private lazy val errorOverlay: dom.HTMLElement      = Dom.byId("error-overlay")
  private lazy val fullscreenBtn: dom.HTMLButtonElement = Dom.byId("fullscreen-btn")

  private def cleanupAnimation(): Unit =
    rafIds.foreach(dom.window.cancelAnimationFrame)
    rafIds = Nil

  private def executeThreeCode(code: String, width: Int, height: Int, view: Option[McpAppView[IO]]): Unit =
    cleanupAnimation()
    lastError = None
    errorOverlay.style.display = "none"

    val visibilityAwareRAF: js.Function1[js.Function1[Double, Unit], Int] = (cb: js.Function1[Double, Unit]) => {
      val id = dom.window.requestAnimationFrame(cb)
      rafIds = rafIds :+ id
      id
    }

    try
      val fn = new js.Function(
        "ctx",
        "canvas",
        "width",
        "height",
        "requestAnimationFrame",
        s"const { THREE, OrbitControls, EffectComposer, RenderPass, UnrealBloomPass } = ctx;\nreturn (async () => { $code })();"
      )
      val promise = fn.call(js.undefined, Three.context, canvas, width.toDouble, height.toDouble, visibilityAwareRAF).asInstanceOf[js.Promise[Unit]]
      val onReject: js.Function1[Any, Unit] = (err: Any) => {
        val errStr = if err != null then err.toString else "Unknown error"
        handleExecutionError(errStr, view)
        ()
      }
      val _ = promise.`catch`[Unit](onReject)
      isRendering = true
    catch
      case t: Throwable =>
        handleExecutionError(t.getMessage, view)

  private def handleExecutionError(errMsg: String, view: Option[McpAppView[IO]]): Unit =
    lastError = Some(errMsg)
    isRendering = false
    errorOverlay.textContent = s"Error: $errMsg"
    errorOverlay.style.display = "flex"
    view.foreach { v =>
      if v.hostCapabilities.updateModelContext.isDefined then
        val content = List(ContentBlock.Text(s"Three.js scene execution error:\n$errMsg"))
        val structured = JsonObject("error" -> Json.fromString(errMsg), "success" -> Json.fromBoolean(false))
        val _ = v.updateModelContext(content, Some(structured)).unsafeRunAndForget()(using cats.effect.unsafe.implicits.global)
    }

  private def renderScene(code: String, height: Int, view: Option[McpAppView[IO]]): Unit =
    currentCode = code
    currentHeight = height
    shimmer.style.display = "none"
    canvas.style.display = "block"

    val width = if container.clientWidth > 0 then container.clientWidth.toInt else 600
    canvas.width = width
    canvas.height = height
    canvas.style.height = s"${height}px"
    container.style.height = s"${height}px"

    executeThreeCode(code, width, height, view)

  private def toggleFullscreen(view: McpAppView[IO]): IO[Unit] =
    val newMode = if currentDisplayMode == "fullscreen" then DisplayMode.`inline` else DisplayMode.fullscreen
    view.requestDisplayMode(newMode)
      .map { chosen =>
        currentDisplayMode = chosen.asJson.asString.getOrElse("inline")
        if currentDisplayMode == "fullscreen" then
          container.classList.add("fullscreen")
          val w = dom.window.innerWidth.toInt
          val h = dom.window.innerHeight.toInt
          canvas.width = w
          canvas.height = h
          canvas.style.height = s"${h}px"
          container.style.height = s"${h}px"
          executeThreeCode(currentCode, w, h, Some(view))
        else
          container.classList.remove("fullscreen")
          renderScene(currentCode, currentHeight, Some(view))
      }
      .handleErrorWith(_ => IO.unit)

  override def tools: IO[Option[ViewToolProvider[IO]]] =
    ViewTools.of[IO](
      ViewTool[SetSceneSourceArgs](
        "set-scene-source",
        "Update the Three.js scene source code. The code will be executed in a sandboxed environment with access to THREE, OrbitControls, EffectComposer, RenderPass, UnrealBloomPass, canvas, width, and height.",
        title = Some("Set Scene Source")
      ) { args =>
        IO {
          val h = args.height.getOrElse(currentHeight)
          renderScene(args.code, h, None)
          val result = SceneResult(success = lastError.isEmpty, error = lastError)
          CallToolResult.structured(result.asJson.noSpaces, result.asJson)
        }
      },
      ViewTool[NoArgs](
        "get-scene-info",
        "Get the current Three.js scene state including source code, dimensions, rendering status, and any errors.",
        title = Some("Get Scene Info")
      ) { _ =>
        IO {
          val info = SceneInfo(
            code = Some(currentCode),
            height = currentHeight,
            error = lastError,
            isRendering = isRendering
          )
          CallToolResult.structured(info.asJson.noSpaces, info.asJson)
        }
      }
    ).map(Some(_))

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    ui.onTeardown(IO(cleanupAnimation())) >>
      view.hostContext.get.flatMap { ctx =>
        IO {
          if ctx.availableDisplayModes.exists(_.contains(DisplayMode.fullscreen)) then
            fullscreenBtn.classList.add("available")
          currentDisplayMode = ctx.displayMode.map(_.asJson.asString.getOrElse("inline")).getOrElse("inline")
          if currentDisplayMode == "fullscreen" then container.classList.add("fullscreen")

          ui.onClick(fullscreenBtn)(toggleFullscreen(view))

          dom.document.addEventListener("keydown", (e: dom.KeyboardEvent) => {
            if e.key == "Escape" && currentDisplayMode == "fullscreen" then
              val _ = toggleFullscreen(view).unsafeRunAndForget()(using cats.effect.unsafe.implicits.global)
          })
        }
      } >>
      view.events.evalMap {
        case HostEvent.ToolInputPartial(arguments) =>
          IO {
            val partialCode = arguments("code").flatMap(_.asString).getOrElse("")
            val h = arguments("height").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(currentHeight)
            if partialCode.nonEmpty then
              canvas.style.display = "none"
              shimmer.style.display = "flex"
              shimmer.style.height = s"${h}px"
              shimmerCode.textContent = partialCode
          }

        case HostEvent.ToolInput(arguments) =>
          IO {
            val code = arguments("code").flatMap(_.asString).getOrElse(DefaultCode)
            val h = arguments("height").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(400)
            renderScene(code, h, Some(view))
          }

        case HostEvent.ToolResult(_) =>
          IO.unit

        case HostEvent.ToolCancelled(_) =>
          IO(cleanupAnimation())

        case HostEvent.Unreadable(_, _, _) =>
          IO.unit
      }.compile.drain
