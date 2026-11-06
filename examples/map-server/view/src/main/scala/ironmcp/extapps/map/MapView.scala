package ironmcp.extapps.map

import cats.effect.IO
import io.circe.{Codec, Decoder, Json}
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.string.Match
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.{CallToolResult, ContentBlock, Implementation}
import ironmcp.schema.JsonSchemaOf
import org.scalajs.dom
import org.scalajs.dom.{HTMLButtonElement, HTMLCanvasElement, HTMLElement}

import scala.scalajs.js

final case class NoArgs() derives Decoder, JsonSchemaOf

final case class CameraPosition(
    longitude: Double,
    latitude: Double,
    height: Double,
    heading: Double,
    pitch: Double,
    roll: Double
) derives Codec.AsObject

object MapView extends ExampleView:

  val appInfo: Implementation = Implementation(name = "CesiumJS Globe", version = "1.0.0")

  private val CesiumVersion = "1.123"
  private val CesiumBaseUrl = s"https://cesium.com/downloads/cesiumjs/releases/$CesiumVersion/Build/Cesium"

  private var viewer: js.Dynamic              = null
  private var viewUUID: Option[String]        = None
  private var currentDisplayMode: String      = "inline"
  private var labelElement: Option[HTMLElement] = None

  private lazy val fullscreenBtn: HTMLButtonElement = Dom.byId("fullscreen-btn")
  private lazy val expandIcon: HTMLElement        = Dom.byId("expand-icon")
  private lazy val compressIcon: HTMLElement      = Dom.byId("compress-icon")
  private lazy val loadingEl: HTMLElement         = Dom.byId("loading")

  private def cesium: js.Dynamic = js.Dynamic.global.Cesium

  private def loadCesium: IO[Unit] = IO.async_ { cb =>
    if js.typeOf(js.Dynamic.global.Cesium) != "undefined" then cb(Right(()))
    else
      val cssLink = dom.document.createElement("link").asInstanceOf[dom.HTMLLinkElement]
      cssLink.rel = "stylesheet"
      cssLink.href = s"$CesiumBaseUrl/Widgets/widgets.css"
      dom.document.head.appendChild(cssLink)

      val script = dom.document.createElement("script")
      script.asInstanceOf[js.Dynamic].src = s"$CesiumBaseUrl/Cesium.js"
      script.asInstanceOf[js.Dynamic].onload = (_: dom.Event) => {
        dom.window.asInstanceOf[js.Dynamic].CESIUM_BASE_URL = CesiumBaseUrl
        cb(Right(()))
      }
      script.asInstanceOf[js.Dynamic].onerror = (_: js.Any) => cb(Left(new RuntimeException("Failed to load CesiumJS from CDN")))
      val _ = dom.document.head.appendChild(script)
  }

  private def captureScreenshot(sourceCanvas: HTMLCanvasElement, maxDim: Int): Option[(String, Int, Int)] =
    val origW = sourceCanvas.width
    val origH = sourceCanvas.height
    if origW <= 0 || origH <= 0 then None
    else
      val scale = Math.min(1.0, maxDim.toDouble / Math.max(origW, origH).toDouble)
      val targetW = Math.max(1, (origW * scale).toInt)
      val targetH = Math.max(1, (origH * scale).toInt)

      val temp = dom.document.createElement("canvas").asInstanceOf[HTMLCanvasElement]
      temp.width = targetW
      temp.height = targetH
      val ctx = temp.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
      if ctx == null then None
      else
        ctx.drawImage(sourceCanvas, 0, 0, targetW.toDouble, targetH.toDouble)
        val dataUrl = temp.toDataURL("image/png")
        val parts = dataUrl.split(",")
        if parts.length > 1 then Some((parts(1), targetW, targetH))
        else None

  private def setViewToBoundingBox(v: js.Dynamic, bbox: BoundingBox): Unit =
    val rect = cesium.Rectangle.fromDegrees(bbox.west, bbox.south, bbox.east, bbox.north)
    val _ = v.camera.setView(js.Dynamic.literal("destination" -> rect))

  private def flyToBoundingBox(v: js.Dynamic, bbox: BoundingBox, duration: Double): IO[Unit] = IO.async_ { cb =>
    val rect = cesium.Rectangle.fromDegrees(bbox.west, bbox.south, bbox.east, bbox.north)
    val flyOptions = js.Dynamic.literal(
      "destination" -> rect,
      "duration"    -> duration,
      "complete"    -> (() => cb(Right(()))),
      "cancel"      -> (() => cb(Right(())))
    )
    val _ = v.camera.flyTo(flyOptions)
  }

  private def setLabel(text: Option[String]): Unit =
    val el = labelElement.getOrElse {
      val div = dom.document.createElement("div").asInstanceOf[HTMLElement]
      div.style.position = "absolute"
      div.style.top = "10px"
      div.style.left = "10px"
      div.style.background = "rgba(0, 0, 0, 0.7)"
      div.style.color = "white"
      div.style.padding = "8px 12px"
      div.style.borderRadius = "4px"
      div.style.fontFamily = "sans-serif"
      div.style.fontSize = "14px"
      div.style.zIndex = "100"
      div.style.setProperty("pointer-events", "none")
      dom.document.body.appendChild(div)
      labelElement = Some(div)
      div
    }
    text match
      case Some(t) if t.nonEmpty =>
        el.textContent = t
        el.style.display = "block"
      case _ =>
        el.style.display = "none"

  private def persistViewState(): Unit =
    for
      uuid <- viewUUID
      v    <- Option(viewer)
    do
      try
        val camera = v.camera
        val carto = camera.positionCartographic
        val lon = cesium.Math.toDegrees(carto.longitude).asInstanceOf[Double]
        val lat = cesium.Math.toDegrees(carto.latitude).asInstanceOf[Double]
        val height = carto.height.asInstanceOf[Double]
        val heading = camera.heading.asInstanceOf[Double]
        val pitch = camera.pitch.asInstanceOf[Double]
        val roll = camera.roll.asInstanceOf[Double]
        val pos = CameraPosition(lon, lat, height, heading, pitch, roll)
        dom.window.localStorage.setItem(uuid, pos.asJson.noSpaces)
      catch
        case _: Throwable => ()

  private def restorePersistedView(): Boolean =
    val uuidOpt = viewUUID
    val storedOpt = uuidOpt.flatMap(u => Option(dom.window.localStorage.getItem(u)))
    storedOpt.flatMap(s => io.circe.parser.decode[CameraPosition](s).toOption) match
      case Some(pos) if viewer != null =>
        try
          viewer.camera.setView(js.Dynamic.literal(
            "destination" -> cesium.Cartesian3.fromDegrees(pos.longitude, pos.latitude, pos.height),
            "orientation" -> js.Dynamic.literal(
              "heading" -> pos.heading,
              "pitch"   -> pos.pitch,
              "roll"    -> pos.roll
            )
          ))
          true
        catch
          case _: Throwable => false
      case _ => false

  private def updateFullscreenButton(context: HostContext): Unit =
    val canFullscreen = context.availableDisplayModes.exists(_.contains(DisplayMode.fullscreen))
    fullscreenBtn.style.display = if canFullscreen then "flex" else "none"
    val insets = context.safeAreaInsets
    val topInset = insets.map(_.top.toDouble).getOrElse(0.0)
    val rightInset = insets.map(_.right.toDouble).getOrElse(0.0)
    fullscreenBtn.style.top = s"${10.0 + topInset}px"
    fullscreenBtn.style.right = s"${10.0 + rightInset}px"

    val isFs = currentDisplayMode == "fullscreen"
    expandIcon.style.display = if isFs then "none" else "block"
    compressIcon.style.display = if isFs then "block" else "none"
    fullscreenBtn.title = if isFs then "Exit fullscreen" else "Enter fullscreen"

  private def toggleFullscreen(view: McpAppView[IO]): IO[Unit] =
    val targetMode = if currentDisplayMode == "fullscreen" then DisplayMode.`inline` else DisplayMode.fullscreen
    view.requestDisplayMode(targetMode).flatMap { chosen =>
      currentDisplayMode = chosen.asJson.asString.getOrElse("inline")
      view.hostContext.get.map { ctx =>
        updateFullscreenButton(ctx)
        if viewer != null then
          val _ = viewer.resize()
          val _ = viewer.scene.requestRender()
      }
    }.handleErrorWith(_ => IO.unit)

  override def tools: IO[Option[ViewToolProvider[IO]]] =
    ViewTools.of[IO](
      ViewTool[NavigateToArgs](
        "navigate-to",
        "Navigate the globe to a new bounding box location",
        title = Some("Navigate To")
      ) { args =>
        if viewer == null then
          IO.pure(CallToolResult(List(ContentBlock.Text("Error: Viewer not initialized")), isError = Some(true)))
        else
          val bbox = BoundingBox(args.west, args.south, args.east, args.north)
          flyToBoundingBox(viewer, bbox, args.duration.getOrElse(2.0)) >> IO {
            setLabel(args.label)
            persistViewState()
            val text = f"Navigated to: W:${bbox.west}%.4f, S:${bbox.south}%.4f, E:${bbox.east}%.4f, N:${bbox.north}%.4f${args.label.map(l => s" ($l)").getOrElse("")}"
            CallToolResult(List(ContentBlock.Text(text)))
          }
      },
      ViewTool[NoArgs](
        "get-current-view",
        "Get the current camera position and bounding box visible on the globe",
        title = Some("Get Current View")
      ) { _ =>
        IO {
          if viewer == null then
            CallToolResult(List(ContentBlock.Text("Error: Viewer not initialized")), isError = Some(true))
          else
            val camera = viewer.camera
            val carto = camera.positionCartographic
            val lat = cesium.Math.toDegrees(carto.latitude).asInstanceOf[Double]
            val lon = cesium.Math.toDegrees(carto.longitude).asInstanceOf[Double]
            val height = carto.height.asInstanceOf[Double]

            val rect = camera.computeViewRectangle()
            val bbox = if js.typeOf(rect) != "undefined" && rect != null then
              Some(BoundingBox(
                west = cesium.Math.toDegrees(rect.west).asInstanceOf[Double],
                south = cesium.Math.toDegrees(rect.south).asInstanceOf[Double],
                east = cesium.Math.toDegrees(rect.east).asInstanceOf[Double],
                north = cesium.Math.toDegrees(rect.north).asInstanceOf[Double]
              ))
            else None

            val res = CurrentViewResult(CurrentViewCamera(lat, lon, height), bbox)
            CallToolResult.structured(res.asJson.spaces2, res.asJson)
        }
      },
      ViewTool[GetScreenshotArgs](
        "get-screenshot",
        "Capture the current globe view as a PNG image for visual analysis",
        title = Some("Get Screenshot")
      ) { args =>
        IO {
          if viewer == null then
            CallToolResult(List(ContentBlock.Text("Error: Viewer not initialized")), isError = Some(true))
          else
            val canvas = viewer.canvas.asInstanceOf[HTMLCanvasElement]
            val maxDim = args.maxDimension.getOrElse(1024)
            captureScreenshot(canvas, maxDim) match
              case Some((data, _, _)) =>
                CallToolResult(List(ContentBlock.Image(data = data.assume[Match["^[A-Za-z0-9+/]*={0,2}$"]], mimeType = "image/png")))
              case None =>
                CallToolResult(List(ContentBlock.Text("Error: screenshot capture failed")), isError = Some(true))
        }
      }
    ).map(Some(_))

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    ui.onTeardown(IO {
      if viewer != null then
        val _ = viewer.destroy()
        viewer = null
    }) >>
      loadCesium >>
      IO {
        cesium.Ion.defaultAccessToken = js.undefined
        cesium.Camera.DEFAULT_VIEW_RECTANGLE = cesium.Rectangle.fromDegrees(-130, 20, -60, 55)

        val viewerOptions = js.Dynamic.literal(
          "baseLayer"            -> false,
          "geocoder"             -> false,
          "baseLayerPicker"      -> false,
          "animation"            -> false,
          "timeline"             -> false,
          "homeButton"           -> false,
          "sceneModePicker"      -> false,
          "navigationHelpButton" -> false,
          "fullscreenButton"     -> false,
          "terrainProvider"      -> js.undefined,
          "contextOptions"       -> js.Dynamic.literal(
            "webgl" -> js.Dynamic.literal(
              "preserveDrawingBuffer" -> true,
              "alpha"                 -> true
            )
          ),
          "useBrowserRecommendedResolution" -> false
        )
        val v = js.Dynamic.newInstance(cesium.Viewer)("cesiumContainer", viewerOptions)
        v.scene.globe.show = true
        v.scene.globe.enableLighting = false
        v.scene.globe.baseColor = cesium.Color.DARKSLATEGRAY
        v.scene.requestRenderMode = false
        v.canvas.style.imageRendering = "auto"
        v.scene.postProcessStages.fxaa.enabled = false

        val osmOptions = js.Dynamic.literal(
          "url"          -> "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
          "minimumLevel" -> 0,
          "maximumLevel" -> 19,
          "credit"       -> js.Dynamic.newInstance(cesium.Credit)("© OpenStreetMap contributors", true)
        )
        val osmProvider = js.Dynamic.newInstance(cesium.UrlTemplateImageryProvider)(osmOptions)
        val _ = v.imageryLayers.addImageryProvider(osmProvider)

        viewer = v
        loadingEl.style.display = "none"

        // Prevent touch scroll propagation
        val canvasEl = v.canvas.asInstanceOf[HTMLCanvasElement]
        for evt <- Seq("touchstart", "touchmove") do
          canvasEl.asInstanceOf[js.Dynamic].addEventListener(evt, (e: dom.Event) => e.preventDefault(), js.Dynamic.literal("passive" -> false))

        ui.onClick(fullscreenBtn)(toggleFullscreen(view))

        dom.document.addEventListener("keydown", (e: dom.KeyboardEvent) => {
          if e.key == "Escape" && currentDisplayMode == "fullscreen" then
            val _ = toggleFullscreen(view).unsafeRunAndForget()(using cats.effect.unsafe.implicits.global)
        })
      } >>
      view.hostContext.get.flatMap { ctx =>
        IO {
          currentDisplayMode = ctx.displayMode.map(_.asJson.asString.getOrElse("inline")).getOrElse("inline")
          updateFullscreenButton(ctx)
        }
      } >>
      view.events.evalMap {
        case HostEvent.ToolInput(arguments) =>
          IO {
            val west = arguments("west").flatMap(_.asNumber).map(_.toDouble).getOrElse(-0.5)
            val south = arguments("south").flatMap(_.asNumber).map(_.toDouble).getOrElse(51.3)
            val east = arguments("east").flatMap(_.asNumber).map(_.toDouble).getOrElse(0.3)
            val north = arguments("north").flatMap(_.asNumber).map(_.toDouble).getOrElse(51.7)
            val label = arguments("label").flatMap(_.asString)

            if viewer != null then
              setViewToBoundingBox(viewer, BoundingBox(west, south, east, north))
              setLabel(label)
              loadingEl.style.display = "none"
          }

        case HostEvent.ToolResult(result) =>
          IO {
            val uuidOpt = result.content.headOption.flatMap {
              case ContentBlock.Text(_, _, Some(meta)) => meta.find(_._1.contains("viewUUID")).flatMap(_._2.asString)
              case _                                   => None
            }
            uuidOpt.foreach { uuid =>
              viewUUID = Some(uuid)
              val _ = restorePersistedView()
            }
          }

        case HostEvent.ToolInputPartial(_) => IO.unit
        case HostEvent.ToolCancelled(_)    => IO.unit
        case HostEvent.Unreadable(_, _, _) => IO.unit
      }.compile.drain
