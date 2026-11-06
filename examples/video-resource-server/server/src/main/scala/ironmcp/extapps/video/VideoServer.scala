package ironmcp.extapps.video

import cats.effect.IO
import cats.syntax.all.*
import io.circe.derivation.{Configuration, ConfiguredEnumCodec}
import io.circe.{Codec, Decoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.{ConfiguredEnumJsonSchemaOf, Default, JsonSchemaOf}
import ironmcp.server.*
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

import java.util.Base64

given Configuration = Configuration.default

enum VideoId derives ConfiguredEnumCodec, ConfiguredEnumJsonSchemaOf:
  case `bunny-1mb`, `bunny-5mb`, `bunny-10mb`, `bunny-20mb`, `bunny-30mb`, `bunny-150mb`

final case class Video(url: String, description: String)

object Library:
  val videos: List[(VideoId, Video)] = List(
    VideoId.`bunny-1mb`   -> Video("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4", "1MB"),
    VideoId.`bunny-5mb`   -> Video("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_5MB.mp4", "5MB"),
    VideoId.`bunny-10mb`  -> Video("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_10MB.mp4", "10MB"),
    VideoId.`bunny-20mb`  -> Video("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_20MB.mp4", "20MB"),
    VideoId.`bunny-30mb`  -> Video("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_30MB.mp4", "30MB"),
    VideoId.`bunny-150mb` -> Video("https://cdn.jsdelivr.net/npm/big-buck-bunny-1080p@0.0.6/video.mp4", "~150MB (full 1080p)")
  )
  val byId: Map[String, Video] = videos.map((id, v) => id.toString -> v).toMap

/** Where video bytes come from: the CDN in the server, canned in tests. */
trait VideoSource:
  def fetch(url: String): IO[Array[Byte]]

object VideoSource:
  def http(client: Client[IO]): VideoSource = url =>
    IO.fromEither(Uri.fromString(url)).flatMap(uri => client.expect[Array[Byte]](uri))

/** The video library as resources: listed one by one, and as the template
  * `videos://{id}`. A read fetches the video and returns it as a base64 blob.
  */
final class VideoResources(source: VideoSource) extends ResourceProvider[IO]:

  def list(params: ListResourcesParams): IO[ListResourcesResult] =
    IO.pure(
      ListResourcesResult(
        Library.videos.map((id, video) =>
          Resource(
            uri = s"videos://$id".refineUnsafe,
            name = s"video-$id".refineUnsafe,
            description = Some(s"Video: ${video.description}".refineUnsafe),
            mimeType = Some("video/mp4")
          )
        ),
        0L,
        CacheScope.`public`
      )
    )

  override def templates(params: ListResourcesParams): IO[ListResourceTemplatesResult] =
    IO.pure(
      ListResourceTemplatesResult(
        List(
          ResourceTemplate(
            uriTemplate = "videos://{id}",
            name = "video",
            description = Some("Video served via MCP resource (base64 blob)"),
            mimeType = Some("video/mp4")
          )
        ),
        0L,
        CacheScope.`public`
      )
    )

  def read(params: ReadResourceParams): IO[ReadResourceResult | InputRequiredResult] =
    val uri: String = params.uri
    uri.stripPrefix("videos://") match
      case id if uri.startsWith("videos://") && Library.byId.contains(id) =>
        val video = Library.byId(id)
        IO.println(s"[video-resource] Fetching: ${video.url}") >>
          source.fetch(video.url).flatMap { bytes =>
            val base64 = Base64.getEncoder.encodeToString(bytes)
            IO.println(s"[video-resource] Size: ${bytes.length} bytes -> ${base64.length} base64 chars")
              .as(ReadResourceResult(List(ResourceContents.Blob(params.uri, base64.assume, Some("video/mp4"))), 0L, CacheScope.`public`))
          }
      case _ => IO.raiseError(McpException.resourceNotFound(uri))

final case class PlayArgs(
    videoId: Option[VideoId :| (Default["bunny-1mb"] DescribedAs "Video ID to play. Available: bunny-1mb, bunny-5mb, bunny-10mb, bunny-20mb, bunny-30mb, bunny-150mb")] = None
) derives Decoder, JsonSchemaOf

final case class Playing(videoUri: String, description: String) derives Codec.AsObject, JsonSchemaOf

/** Upstream's video-resource-server: binary content (video) as MCP resources. */
object VideoServer:

  val ResourceUri: UiUri = "ui://video-player/mcp-app.html"

  private val description =
    "Play a video loaded via MCP resource.\nAvailable videos:\n" +
      Library.videos.map((id, v) => s"- $id: ${v.description}").mkString("\n")

  def apply(html: String, source: VideoSource): McpServer[IO] =
    val play = McpTool[PlayArgs]("play_video", description.refineUnsafe, title = Some("Play Video via Resource")) { args =>
      val id = args.videoId.fold(VideoId.`bunny-1mb`)(v => v)
      IO.pure(Playing(s"videos://$id", Library.byId(id.toString).description))
    }.withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Video Resource Server", version = "1.0.0"),
      tools = Some(ToolSet.of(play)),
      resources = Some(ResourceProvider.all(UiResources.of[IO](UiResource(ResourceUri, "ui://video-player/mcp-app.html", html)), VideoResources(source))),
      extensions = Some(Map(McpApps.serverExtension))
    )

  /** The HTTP client lives as long as the server, which is the process. */
  def load: IO[McpServer[IO]] =
    (ViewPage.load("video-resource-server/view"), EmberClientBuilder.default[IO].build.allocated.map(_._1))
      .mapN((html, client) => apply(html, VideoSource.http(client)))

object Main extends ExampleMain("video-resource-server", _ => VideoServer.load)
