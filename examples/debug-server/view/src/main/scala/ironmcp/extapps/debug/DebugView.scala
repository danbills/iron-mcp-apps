package ironmcp.extapps.debug

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import io.circe.{Json, JsonObject, Printer}
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Ui}
import ironmcp.protocol.*
import org.scalajs.dom

import scala.concurrent.duration.*
import scala.scalajs.js

/** Upstream's debug View: every callback the host can trigger, logged and
  * counted; every request a View can make, one button each; and the View's
  * own tool handler (`oncalltool` / `onlisttools`), answered by hand.
  */
object DebugView extends ExampleView:

  val appInfo: Implementation                = Implementation(name = "Debug App", version = "1.0.0")
  override def capabilities: AppCapabilities = AppCapabilities(tools = Some(ListChanged(Some(true))))
  // Upstream's debug View styles itself and manages auto-resize so it can be toggled.
  override def theming: Boolean    = false
  override def autoResize: Boolean = false

  private final case class Entry(time: Double, kind: String, payload: Json)
  private final case class State(
      log: Vector[Entry] = Vector.empty,
      counts: Map[String, Int] = Map.empty,
      last: Map[String, Json] = Map.empty,
      filter: String = "all"
  )

  private val Callbacks =
    List("ontoolinput", "ontoolinputpartial", "ontoolresult", "ontoolcancelled", "onhostcontextchanged", "onteardown", "oncalltool", "onlisttools", "onerror")

  private inline val RedPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="

  private val state     = Ref.unsafe[IO, State](State())
  private val connected = Deferred.unsafe[IO, McpAppView[IO]]

  /** `oncalltool` / `onlisttools` as upstream sets them: logged, then answered. */
  override def tools: IO[Option[ViewToolProvider[IO]]] = IO.pure(Some(new ViewToolProvider[IO]:
    def list: IO[List[Tool]] = logEvent("onlisttools", Json.obj()).as(Nil)
    def call(name: String, arguments: JsonObject): IO[Option[CallToolResult]] =
      logEvent("oncalltool", Json.obj("name" -> name.asJson, "arguments" -> Json.fromJsonObject(arguments)))
        .as(Some(CallToolResult(List(ContentBlock.Text("App handled tool call")))))
    def changes: Stream[IO, Unit] = Stream.empty
  ))

  // ---------------------------------------------------------------- rendering

  private val pretty = Printer.spaces2.copy(colonLeft = "")

  private def escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def formatTime(ms: Double): String =
    val d = new js.Date(ms)
    def pad(n: Double, width: Int) = n.toInt.toString.reverse.padTo(width, '0').reverse
    s"${pad(d.getHours(), 2)}:${pad(d.getMinutes(), 2)}:${pad(d.getSeconds(), 2)}.${pad(d.getMilliseconds(), 3)}"

  private def truncate(payload: Json): String =
    val text = payload.noSpaces
    if text.length > 100 then text.take(100) + "..." else text

  private def renderEventLog(s: State): IO[Unit] = IO {
    val el       = Dom.byId[dom.HTMLElement]("event-log")
    val filtered = if s.filter == "all" then s.log else s.log.filter(_.kind == s.filter)
    el.innerHTML = filtered.zipWithIndex.map { (entry, index) =>
      s"""
    <div class="log-entry" data-index="$index">
      <div class="log-entry-header">
        <span class="log-toggle">▶</span>
        <span class="log-time">[${formatTime(entry.time)}]</span>
        <span class="log-type ${escape(entry.kind)}">${escape(entry.kind)}:</span>
        <span class="log-payload-preview">${escape(truncate(entry.payload))}</span>
      </div>
      <pre class="log-payload-full">${escape(pretty.print(entry.payload))}</pre>
    </div>
  """
    }.mkString
    Dom.queryAll[dom.HTMLElement]("#event-log .log-entry").foreach(entry =>
      entry.addEventListener("click", (_: dom.Event) => { val _ = entry.classList.toggle("expanded") })
    )
    el.scrollTop = el.scrollHeight.toDouble
  }

  private def renderCallbackStatus(s: State): IO[Unit] = IO {
    Dom.byId[dom.HTMLElement]("callback-table-body").innerHTML = Callbacks.map { name =>
      val registered = name != "onerror"
      s"""
      <tr>
        <td><code>$name</code></td>
        <td class="${if registered then "registered-yes" else "registered-no"}">${if registered then "✓" else "✗"}</td>
        <td>${s.counts.getOrElse(name, 0)}</td>
        <td class="payload-preview">${s.last.get(name).fold("-")(p => escape(truncate(p)))}</td>
      </tr>
    """
    }.mkString
  }

  /** A JSON value as JavaScript's `String(value)` writes it. */
  private def jsString(value: Json): String =
    value.fold("null", _.toString, n => n.toDouble.toString, identity, _ => "[object Array]", _ => "[object Object]")

  private def renderHostInfo(view: McpAppView[IO], ctx: HostContext): IO[Unit] = IO {
    def tick(present: Boolean) = if present then "✓" else "✗"
    val caps = view.hostCapabilities
    Dom.byId[dom.HTMLElement]("host-context-info").innerHTML = s"""
      <dt>Theme</dt><dd>${ctx.theme.fold("unknown")(_.toString)}</dd>
      <dt>Locale</dt><dd>${escape(ctx.locale.getOrElse("unknown"))}</dd>
      <dt>TimeZone</dt><dd>${escape(ctx.timeZone.getOrElse("unknown"))}</dd>
      <dt>Platform</dt><dd>${ctx.platform.fold("unknown")(_.toString)}</dd>
      <dt>Display Mode</dt><dd>${ctx.displayMode.fold("unknown")(_.toString)}</dd>
      <dt>Host</dt><dd>${escape(view.hostInfo.name)} v${escape(view.hostInfo.version)}</dd>
    """
    Dom.byId[dom.HTMLElement]("host-capabilities-info").innerHTML = s"""
      <dt>openLinks</dt><dd>${tick(caps.openLinks.isDefined)}</dd>
      <dt>serverTools</dt><dd>${tick(caps.serverTools.isDefined)}</dd>
      <dt>serverResources</dt><dd>${tick(caps.serverResources.isDefined)}</dd>
      <dt>logging</dt><dd>${tick(caps.logging.isDefined)}</dd>
      <dt>message</dt><dd>${tick(caps.message.isDefined)}</dd>
      <dt>updateModelContext</dt><dd>${tick(caps.updateModelContext.isDefined)}</dd>
    """
    val container = Dom.byId[dom.HTMLElement]("host-container-info")
    ctx.raw("containerDimensions").flatMap(_.asObject) match
      case Some(dims) =>
        def side(exact: String, max: String) =
          dims(exact).fold(s"max ${dims(max).fold("?")(jsString)}px")(v => s"${jsString(v)}px")
        val safe = ctx.safeAreaInsets.fold("none")(i => s"T${i.top} R${i.right} B${i.bottom} L${i.left}")
        container.innerHTML = s"""
      <dt>Width</dt><dd>${side("width", "maxWidth")}</dd>
      <dt>Height</dt><dd>${side("height", "maxHeight")}</dd>
      <dt>Safe Area</dt><dd>$safe</dd>
    """
      case None => container.innerHTML = "<dd>No container info</dd>"
    val sample = Dom.byId[dom.HTMLElement]("host-styles-sample")
    ctx.raw("styles").flatMap(_.asObject) match
      case Some(styles) =>
        sample.innerHTML = styles.toList.take(6).map { (key, value) =>
          val color = escape(jsString(value))
          s"""<div class="style-swatch" style="background: $color;" title="${escape(key)}: $color"></div>"""
        }.mkString
      case None => sample.innerHTML = "<span>No styles</span>"
  }

  private val updateCurrentSize: IO[Unit] = IO {
    val html = dom.document.documentElement
    Dom.byId[dom.HTMLElement]("current-size").textContent = s"${html.scrollWidth}x${html.scrollHeight}"
  }

  // ---------------------------------------------------------------- logging

  /** Logs to the console, the page and the server's log file (upstream's `logEvent`). */
  private def logEvent(kind: String, payload: Json): IO[Unit] =
    for
      now <- IO.realTime
      _   <- IO(dom.console.log(s"[debug-app] $kind:", payload.noSpaces))
      s <- state.updateAndGet(s =>
             s.copy(
               log = (s.log :+ Entry(now.toMillis.toDouble, kind, payload)).takeRight(100),
               counts = s.counts.updated(kind, s.counts.getOrElse(kind, 0) + 1),
               last = s.last.updated(kind, payload)
             )
           )
      _    <- renderEventLog(s) >> renderCallbackStatus(s)
      view <- connected.tryGet
      _    <- view.traverse_(sendToServerLog(_, kind, payload).start.void)
    yield ()

  private def sendToServerLog(view: McpAppView[IO], kind: String, payload: Json): IO[Unit] =
    view
      .callServerTool("debug-log", JsonObject("type" -> kind.asJson, "payload" -> payload))
      .void
      .handleErrorWith(e => IO(dom.console.error("[debug-app] Failed to send log to server:", e.toString)))

  private def failed(error: Throwable): IO[Unit] = logEvent("error", Json.obj("message" -> Option(error.getMessage).getOrElse(error.toString).asJson))

  private def outcome(accepted: Boolean): Json = if accepted then Json.obj() else Json.obj("isError" -> true.asJson)

  private def resultJson(result: CallToolResult): Json = result.asJson.deepDropNullValues

  // ---------------------------------------------------------------- the View

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    for
      autoResize <- Ref.of[IO, Option[IO[Unit]]](None)
      _          <- connected.complete(view)
      initial    <- state.get
      _          <- renderCallbackStatus(initial)
      _          <- ui.onTeardown(logEvent("onteardown", Json.obj()))
      _          <- IO(wire(view, ui, autoResize))
      _          <- logEvent("connected", Json.obj("success" -> true.asJson))
      ctx        <- view.hostContext.get
      _          <- SafeArea[IO](ctx, main) >> renderHostInfo(view, ctx) >> updateCurrentSize
      _          <- AutoResize(view).allocated.flatMap((_, release) => autoResize.set(Some(release)))
      _          <- (updateCurrentSize >> IO.sleep(1.second)).foreverM.background.surround(listen(view))
    yield ()

  private def main: dom.HTMLElement = Dom.query[dom.HTMLElement](".main")

  private def listen(view: McpAppView[IO]): IO[Unit] =
    val contextChanges = view.hostContext.discrete
      .drop(1)
      .evalMap(ctx => logEvent("onhostcontextchanged", ctx.asJson) >> SafeArea[IO](ctx, main) >> renderHostInfo(view, ctx))
    val events = view.events.evalMap {
      case HostEvent.ToolInput(arguments)        => logEvent("ontoolinput", Json.obj("arguments" -> Json.fromJsonObject(arguments)))
      case HostEvent.ToolInputPartial(arguments) => logEvent("ontoolinputpartial", Json.obj("arguments" -> Json.fromJsonObject(arguments)))
      case HostEvent.ToolResult(result)          => logEvent("ontoolresult", resultJson(result))
      case HostEvent.ToolCancelled(reason)       => logEvent("ontoolcancelled", Json.obj("reason" -> reason.asJson).dropNullValues)
      case HostEvent.Unreadable(method, _, why)  => logEvent("onerror", Json.obj("method" -> method.asJson, "error" -> why.asJson))
    }
    events.merge(contextChanges).compile.drain

  private def wire(view: McpAppView[IO], ui: Ui, autoResize: Ref[IO, Option[IO[Unit]]]): Unit =
    // Collapsible sections.
    Dom.queryAll[dom.HTMLElement](".section-header[data-toggle]").foreach { header =>
      header.addEventListener("click", (_: dom.Event) => Option(header.closest(".section")).foreach(s => { val _ = s.classList.toggle("collapsed") }))
    }

    val filter = Dom.byId[dom.HTMLSelectElement]("log-filter")
    ui.on(filter, "change")(_ => state.updateAndGet(_.copy(filter = filter.value)).flatMap(renderEventLog))
    ui.onClick(Dom.byId("clear-log-btn"))(state.updateAndGet(_.copy(log = Vector.empty)).flatMap(renderEventLog))

    // Messages.
    def send(content: ContentBlock): IO[Unit] =
      view.sendMessage(List(content)).flatMap(ok => logEvent("send-message-result", outcome(ok))).handleErrorWith(failed)
    ui.onClick(Dom.byId("send-message-text-btn"))(send(ContentBlock.Text(Dom.byId[dom.HTMLInputElement]("message-text").value)))
    ui.onClick(Dom.byId("send-message-image-btn"))(send(ContentBlock.Image(RedPng, "image/png")))

    // Logging.
    for (id, level) <- List("log-debug-btn" -> LoggingLevel.debug, "log-info-btn" -> LoggingLevel.info, "log-warning-btn" -> LoggingLevel.warning, "log-error-btn" -> LoggingLevel.error) do
      ui.onClick(Dom.byId(id)) {
        val data = Dom.byId[dom.HTMLInputElement]("log-data").value
        view.log(level, Json.fromString(data)) >> logEvent("send-log", Json.obj("level" -> level.toString.asJson, "data" -> data.asJson))
      }

    // Model context.
    ui.onClick(Dom.byId("update-context-text-btn")) {
      val text = Dom.byId[dom.HTMLInputElement]("context-text").value
      (view.updateModelContext(List(ContentBlock.Text(text))) >> logEvent("update-context", Json.obj("type" -> "text".asJson, "value" -> text.asJson)))
        .handleErrorWith(failed)
    }
    ui.onClick(Dom.byId("update-context-structured-btn")) {
      (state.get, IO.realTimeDate).flatMapN { (s, now) =>
        val debugState = JsonObject("debugState" -> Json.obj("eventCount" -> s.log.size.asJson, "timestamp" -> now.toISOString().asJson))
        view.updateModelContext(structuredContent = Some(debugState)) >> logEvent("update-context", Json.obj("type" -> "structured".asJson))
      }.handleErrorWith(failed)
    }
    ui.onClick(Dom.byId("update-context-image-btn")) {
      (view.updateModelContext(List(ContentBlock.Image(RedPng, "image/png"))) >> logEvent("update-context", Json.obj("type" -> "image".asJson)))
        .handleErrorWith(failed)
    }

    // Display mode.
    for (id, mode) <- List("display-inline-btn" -> DisplayMode.`inline`, "display-fullscreen-btn" -> DisplayMode.fullscreen, "display-pip-btn" -> DisplayMode.pip) do
      ui.onClick(Dom.byId(id)) {
        view
          .requestDisplayMode(mode)
          .flatMap(chosen => logEvent("display-mode-result", Json.obj("mode" -> mode.asJson, "result" -> Json.obj("mode" -> chosen.asJson))))
          .handleErrorWith(failed)
      }

    // Links.
    ui.onClick(Dom.byId("open-link-btn")) {
      view.openLink(Dom.byId[dom.HTMLInputElement]("link-url").value).flatMap(ok => logEvent("open-link-result", outcome(ok))).handleErrorWith(failed)
    }

    // Size.
    val toggle = Dom.byId[dom.HTMLInputElement]("auto-resize-toggle")
    ui.on(toggle, "change") { _ =>
      val change =
        if toggle.checked then
          autoResize.get.flatMap {
            case Some(_) => IO.unit
            case None    => AutoResize(view).allocated.flatMap((_, release) => autoResize.set(Some(release)))
          }
        else autoResize.getAndSet(None).flatMap(_.sequence_)
      change >> logEvent("auto-resize-toggle", Json.obj("enabled" -> toggle.checked.asJson))
    }
    for (id, (w, h)) <- List("resize-200x100-btn" -> (200, 100), "resize-400x300-btn" -> (400, 300), "resize-800x600-btn" -> (800, 600)) do
      ui.onClick(Dom.byId(id))(view.sendSizeChanged(Some(w), Some(h)) >> logEvent("manual-resize", Json.obj("width" -> w.asJson, "height" -> h.asJson)))

    // Server tools.
    ui.onClick(Dom.byId("call-debug-tool-btn")) {
      val delay = js.Dynamic.global.parseInt(Dom.byId[dom.HTMLInputElement]("tool-delay-ms").value, 10).asInstanceOf[Double]
      val args = JsonObject(
        "contentType"              -> Dom.byId[dom.HTMLSelectElement]("tool-content-type").value.asJson,
        "multipleBlocks"           -> Dom.byId[dom.HTMLInputElement]("tool-multiple-blocks").checked.asJson,
        "includeStructuredContent" -> Dom.byId[dom.HTMLInputElement]("tool-structured-content").checked.asJson,
        "includeMeta"              -> Dom.byId[dom.HTMLInputElement]("tool-include-meta").checked.asJson,
        "simulateError"            -> Dom.byId[dom.HTMLInputElement]("tool-simulate-error").checked.asJson
      ).add("delayMs", if delay.isNaN || delay == 0 then Json.Null else delay.asJson).filter(!_._2.isNull)
      (logEvent("call-server-tool", Json.obj("name" -> "debug-tool".asJson, "arguments" -> Json.fromJsonObject(args))) >>
        view.callServerTool("debug-tool", args).flatMap(r => logEvent("server-tool-result", resultJson(r)))).handleErrorWith(failed)
    }
    ui.onClick(Dom.byId("call-debug-refresh-btn")) {
      (logEvent("call-server-tool", Json.obj("name" -> "debug-refresh".asJson, "arguments" -> Json.obj())) >>
        view.callServerTool("debug-refresh").flatMap(r => logEvent("server-tool-result", resultJson(r)))).handleErrorWith(failed)
    }
