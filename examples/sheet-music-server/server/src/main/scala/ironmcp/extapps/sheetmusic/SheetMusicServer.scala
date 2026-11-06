package ironmcp.extapps.sheetmusic

import cats.effect.std.Mutex
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Decoder
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.{Default, JsonSchemaOf}
import ironmcp.server.*
import org.graalvm.polyglot.{Context, Engine, Source}

import scala.io.Source as IoSource

/** abcjs's own parser, run on the JVM in GraalJS, so a tune is checked exactly
  * as upstream's Node server checks it (`ABCJS.parseOnly`). One context, one
  * caller at a time: a GraalJS context is single-threaded.
  */
final class AbcParser private (parseOnly: org.graalvm.polyglot.Value, lock: Mutex[IO]):

  /** abcjs's warnings for a tune, tags stripped; none means it parsed. */
  def warnings(abc: String): IO[List[String]] =
    lock.lock.surround(IO.blocking {
      val result = parseOnly.execute(abc)
      (0L until result.getArraySize).toList.map(i => result.getArrayElement(i).asString.replaceAll("<[^>]*>", ""))
    })

object AbcParser:
  private val Script = "abcjs/abcjs-basic-min.js"

  def resource: Resource[IO, AbcParser] =
    for
      // Truffle's optimizing runtime is optional here; do not warn when its native part cannot attach.
      _       <- Resource.eval(IO(if sys.props.get("polyglotimpl.AttachLibraryFailureAction").isEmpty then sys.props("polyglotimpl.AttachLibraryFailureAction") = "ignore"))
      engine  <- Resource.fromAutoCloseable(IO.blocking(Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build()))
      context <- Resource.fromAutoCloseable(IO.blocking(Context.newBuilder("js").engine(engine).build()))
      fn <- Resource.eval(IO.blocking {
              val code = IoSource.fromResource(Script)(using scala.io.Codec.UTF8).mkString
              context.eval(Source.newBuilder("js", code, Script).build())
              context.eval("js", "(abc) => { const tunes = ABCJS.parseOnly(abc); return (tunes[0] && tunes[0].warnings) || []; }")
            })
      lock <- Resource.eval(Mutex[IO])
    yield AbcParser(fn, lock)

final case class PlayArgs(
    abcNotation: Option[
      String :| (Default[
        "X:1\nT:Twinkle, Twinkle Little Star\nM:4/4\nL:1/4\nK:C\nC C G G | A A G2 | F F E E | D D C2 |\nG G F F | E E D2 | G G F F | E E D2 |\nC C G G | A A G2 | F F E E | D D C2 |"
      ] DescribedAs "ABC notation string to render as sheet music with audio playback")
    ] = None
) derives Decoder, JsonSchemaOf

/** Upstream's sheet-music-server: the View renders and plays ABC notation; the
  * server only checks it parses.
  */
object SheetMusicServer:

  val ResourceUri: UiUri = "ui://sheet-music/mcp-app.html"

  val DefaultTune: String =
    """X:1
      |T:Twinkle, Twinkle Little Star
      |M:4/4
      |L:1/4
      |K:C
      |C C G G | A A G2 | F F E E | D D C2 |
      |G G F F | E E D2 | G G F F | E E D2 |
      |C C G G | A A G2 | F F E E | D D C2 |""".stripMargin

  def apply(html: String, parser: AbcParser): McpServer[IO] =
    val play = McpTool[PlayArgs](
      "play-sheet-music",
      "Plays music from ABC notation with audio playback and visual sheet music. " +
        "Use this to compose original songs (for birthdays, holidays, or any occasion) " +
        "or perform well-known tunes (folk songs, nursery rhymes, hymns, classical melodies). " +
        "For accurate renditions of well-known tunes, look up the ABC notation from " +
        "abcnotation.com or thesession.org rather than recalling from memory.",
      title = Some("Play Sheet Music")
    )(args =>
      parser.warnings(args.abcNotation.fold(DefaultTune)(a => a)).map {
        case Nil      => Right("Input parsed successfully.")
        case warnings => Left(s"Invalid ABC notation:\n${warnings.mkString("\n")}")
      }
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Sheet Music Server", version = "1.0.0"),
      tools = Some(ToolSet.of(play)),
      resources = Some(
        UiResources.of[IO](
          UiResource(
            ResourceUri,
            "ui://sheet-music/mcp-app.html",
            html,
            Some("Sheet Music Viewer UI"),
            // abcjs fetches its soundfonts from here.
            UiResourceMeta(csp = Some(UiCsp(connectDomains = Some(List("https://paulrosen.github.io")))))
          )
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  /** The parser lives as long as the server, which is the process. */
  def load: IO[McpServer[IO]] =
    (ViewPage.load("sheet-music-server/view"), AbcParser.resource.allocated.map(_._1)).mapN(apply)

object Main extends ExampleMain("sheet-music-server", _ => SheetMusicServer.load)
