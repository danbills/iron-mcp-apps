package ironmcp.extapps.debug

import cats.Show
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.derivation.{Configuration, ConfiguredEnumCodec}
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Json, JsonObject}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, JsDate, NoArgs, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.{ConfiguredEnumJsonSchemaOf, Default, JsonSchemaOf}
import ironmcp.server.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*

given Configuration = Configuration.default

enum ContentType derives ConfiguredEnumCodec, ConfiguredEnumJsonSchemaOf:
  case text, image, audio, resource, resourceLink, mixed

/** `debug-tool`'s input. Every field may be left out; the defaults are
  * upstream's, applied by [[DebugInput.resolved]] (and advertised in the schema).
  */
final case class DebugInput(
    contentType: Option[ContentType :| Default["text"]] = None,
    multipleBlocks: Option[Boolean :| Default[true]] = None,
    includeStructuredContent: Option[Boolean :| Default[true]] = None,
    includeMeta: Option[Boolean :| Default[true]] = None,
    largeInput: Option[String] = None,
    simulateError: Option[Boolean :| Default[false]] = None,
    delayMs: Option[Double] = None
) derives Decoder, JsonSchemaOf

/** The input with its defaults applied, as zod's `.default(...)` gives it to upstream's handler. */
final case class Resolved(
    contentType: ContentType,
    multipleBlocks: Boolean,
    includeStructuredContent: Boolean,
    includeMeta: Boolean,
    largeInput: Option[String],
    simulateError: Boolean,
    delayMs: Option[Double]
) derives Codec.AsObject

object DebugInput:
  extension (in: DebugInput)
    def resolved: Resolved = Resolved(
      in.contentType.fold(ContentType.text)(c => c),
      in.multipleBlocks.fold(true)(b => b),
      in.includeStructuredContent.fold(true)(b => b),
      in.includeMeta.fold(true)(b => b),
      in.largeInput,
      in.simulateError.fold(false)(b => b),
      in.delayMs
    )

/** `debug-tool`'s declared output. Its result is built by hand (any content
  * kind, optional structured content), so it carries this schema itself.
  */
final case class DebugOutput(config: Map[String, Json], timestamp: String, counter: Int, largeInputLength: Option[Int])
    derives Codec.AsObject, JsonSchemaOf

final case class DebugResult(result: CallToolResult)
object DebugResult:
  given ToolOutput[DebugResult] with
    val schema: Option[JsonObject]                  = summon[JsonSchemaOf[DebugOutput]].schema.asObject
    def result(value: DebugResult): CallToolResult = value.result

final case class Refreshed(timestamp: String, counter: Int) derives Codec.AsObject, JsonSchemaOf
object Refreshed:
  given Show[Refreshed] = r => s"Server timestamp: ${r.timestamp}"

final case class LogArgs(`type`: String, payload: Option[Json] = None) derives Decoder, JsonSchemaOf
final case class Logged(logged: Boolean, logFile: String) derives Codec.AsObject, JsonSchemaOf
object Logged:
  given Show[Logged] = l => s"Logged to ${l.logFile}"

/** Upstream's debug-server: one tool for every content kind and result shape,
  * and two app-only tools the View polls and logs with.
  */
object DebugServer:

  val ResourceUri: UiUri = "ui://debug-tool/mcp-app.html"

  private inline val BluePng1x1 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPj/HwADBwIAMCbHYQAAAABJRU5ErkJggg=="
  private inline val SilentWav = "UklGRiYAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQIAAAAAAA=="

  /** `--log-file=...`, else `DEBUG_LOG_FILE`, else upstream's default. */
  def logFile(args: List[String]): Path =
    Path.of(
      args
        .collectFirst { case a if a.startsWith("--log-file=") => a.stripPrefix("--log-file=") }
        .orElse(sys.env.get("DEBUG_LOG_FILE"))
        .getOrElse("/tmp/mcp-apps-debug-server.log")
    )

  def content(args: Resolved): List[ContentBlock] =
    val count = if args.multipleBlocks then 3 else 1
    if args.contentType == ContentType.mixed then
      List(
        ContentBlock.Text("Mixed content: text block"),
        ContentBlock.Image(BluePng1x1, "image/png"),
        ContentBlock.Audio(SilentWav, "audio/wav")
      )
    else
      (0 until count).toList.map { i =>
        val suffix = if args.multipleBlocks then s" #${i + 1}" else ""
        val dashed = suffix.replaceAll("\\s", "-")
        args.contentType match
          case ContentType.text  => ContentBlock.Text(s"Debug text content$suffix")
          case ContentType.image => ContentBlock.Image(BluePng1x1, "image/png")
          case ContentType.audio => ContentBlock.Audio(SilentWav, "audio/wav")
          case ContentType.resource =>
            ContentBlock.Embedded(
              ResourceContents.Text(s"debug://embedded-resource$dashed".refineUnsafe, s"Embedded resource content$suffix", Some("text/plain"))
            )
          case _ =>
            ContentBlock.Link(Resource(uri = s"debug://linked-resource$dashed".refineUnsafe, name = s"Linked Resource$suffix".refineUnsafe, mimeType = Some("text/plain")))
      }

  def apply(html: String, log: Path, counter: Ref[IO, Int]): McpServer[IO] =
    val debugTool = McpTool[DebugInput](
      "debug-tool",
      "Comprehensive debug tool for testing MCP Apps SDK. Configure content types, error simulation, delays, and more.",
      title = Some("Debug Tool")
    ) { input =>
      val args = input.resolved
      for
        _     <- args.delayMs.filter(_ > 0).fold(IO.unit)(ms => IO.sleep(ms.millis))
        now   <- IO.realTimeInstant
        count <- if args.includeStructuredContent then counter.updateAndGet(_ + 1).map(Some(_)) else IO.pure(None)
      yield
        val config     = args.asJson.deepDropNullValues.asObject.fold(Map.empty[String, Json])(_.toMap)
        val structured = count.map(n => DebugOutput(config, JsDate.iso(now), n, args.largeInput.map(_.length)).asJson.deepDropNullValues)
        val meta =
          Option.when(args.includeMeta)(Map[MetaKey, Json](("debugInfo": MetaKey) -> Json.obj("processedAt" -> now.toEpochMilli.asJson, "serverVersion" -> "1.0.0".asJson)))
        DebugResult(
          CallToolResult(
            content = content(args),
            structuredContent = structured,
            isError = Option.when(args.simulateError)(true),
            _meta = meta.map(extra => ResultMeta(extra = extra))
          )
        )
    }.withUi(ResourceUri)

    val refresh = McpTool[NoArgs]("debug-refresh", "App-only tool for polling server state. Not visible to the model.", title = Some("Refresh Debug Info"))(_ =>
      (IO.realTimeInstant, counter.get).mapN((now, n) => Refreshed(JsDate.iso(now), n))
    ).withUi(ResourceUri, Visibility.App)

    val debugLog = McpTool[LogArgs]("debug-log", "App-only tool for logging events to the server log file. Not visible to the model.", title = Some("Log to File")) {
      args =>
        IO.realTimeInstant.flatMap { now =>
          val line = Json.obj("timestamp" -> JsDate.iso(now).asJson, "type" -> args.`type`.asJson, "payload" -> args.payload.asJson).dropNullValues
          IO.blocking(Files.writeString(log, line.noSpaces + "\n", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
            .attempt
            .flatMap(_.fold(e => IO.println(s"[debug-server] Failed to write to log file: $e"), _ => IO.unit))
            .as(Logged(logged = true, log.toString))
        }
    }.withUi(ResourceUri, Visibility.App)

    McpServer(
      info = Implementation(name = "Debug MCP App Server", version = "1.0.0"),
      tools = Some(ToolSet.of(debugTool, refresh, debugLog)),
      resources = Some(UiResources.of[IO](UiResource(ResourceUri, "ui://debug-tool/mcp-app.html", html))),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load(args: List[String]): IO[McpServer[IO]] =
    (ViewPage.load("debug-server/view"), Ref.of[IO, Int](0)).mapN((html, counter) => apply(html, logFile(args), counter))

object Main extends ExampleMain("debug-server", DebugServer.load)
