package ironmcp.extapps.qr

import cats.effect.IO
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.{BarcodeFormat, EncodeHintType}
import io.circe.Decoder
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.{Default, JsonSchemaOf, Titled}
import ironmcp.server.*

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** `generate_qr`'s arguments, as FastMCP derives them from upstream's Python
  * signature: every one optional, with its default and a title.
  */
final case class QrArgs(
    text: Option[String :| (Titled["Text"] & Default["https://modelcontextprotocol.io"])] = None,
    box_size: Option[Int :| (Titled["Box Size"] & Default[10])] = None,
    border: Option[Int :| (Titled["Border"] & Default[4])] = None,
    error_correction: Option[String :| (Titled["Error Correction"] & Default["M"])] = None,
    fill_color: Option[String :| (Titled["Fill Color"] & Default["black"])] = None,
    back_color: Option[String :| (Titled["Back Color"] & Default["white"])] = None
) derives Decoder, JsonSchemaOf

/** Upstream's qr-server (Python, qrcode and Pillow there): a QR code as a PNG
  * image block. ZXing encodes; ImageIO writes the PNG.
  */
object QrServer:

  val ResourceUri: UiUri = "ui://qr-server/view.html"

  private val description =
    """Generate a QR code from text.
      |
      |Args:
      |    text: The text/URL to encode
      |    box_size: Size of each box in pixels (default: 10)
      |    border: Border size in boxes (default: 4)
      |    error_correction: Error correction level - L(7%), M(15%), Q(25%), H(30%)
      |    fill_color: Foreground color (hex like #FF0000 or name like red)
      |    back_color: Background color (hex like #FFFFFF or name like white)""".stripMargin

  private def level(code: String): ErrorCorrectionLevel = code.toUpperCase match
    case "L" => ErrorCorrectionLevel.L
    case "Q" => ErrorCorrectionLevel.Q
    case "H" => ErrorCorrectionLevel.H
    case _   => ErrorCorrectionLevel.M

  /** The PNG, base64; or why not (a bad colour, a size the encoder refuses). */
  def png(args: QrArgs): Either[String, String] =
    val text  = args.text.fold("https://modelcontextprotocol.io")(t => t)
    val box   = args.box_size.fold(10)(b => b)
    val quiet = args.border.fold(4)(b => b)
    for
      fill <- Colors.parse(args.fill_color.fold("black")(c => c)).toRight(s"unknown color specifier: ${args.fill_color.fold("black")(c => c)}")
      back <- Colors.parse(args.back_color.fold("white")(c => c)).toRight(s"unknown color specifier: ${args.back_color.fold("white")(c => c)}")
      _    <- Either.cond(box > 0 && quiet >= 0, (), "box_size must be positive and border not negative")
      image <- scala.util
                 .Try {
                   val hints = new java.util.EnumMap[EncodeHintType, Any](classOf[EncodeHintType])
                   hints.put(EncodeHintType.ERROR_CORRECTION, level(args.error_correction.fold("M")(c => c)))
                   hints.put(EncodeHintType.MARGIN, quiet)
                   hints.put(EncodeHintType.CHARACTER_SET, "UTF-8")
                   val matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
                   val alpha  = fill.getAlpha < 255 || back.getAlpha < 255
                   val img    = new BufferedImage(matrix.getWidth * box, matrix.getHeight * box, if alpha then BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
                   for y <- 0 until img.getHeight; x <- 0 until img.getWidth do
                     img.setRGB(x, y, (if matrix.get(x / box, y / box) then fill else back).getRGB)
                   val out = new ByteArrayOutputStream()
                   ImageIO.write(img, "PNG", out)
                   Base64.getEncoder.encodeToString(out.toByteArray)
                 }
                 .toEither
                 .left
                 .map(e => s"Could not encode: ${e.getMessage}")
    yield image

  def apply(html: String): McpServer[IO] =
    val generate = McpTool[QrArgs]("generate_qr", description.refineUnsafe)(args =>
      IO.blocking(png(args) match
        case Right(data) => CallToolResult(List(ContentBlock.Image(data.assume, "image/png")))
        case Left(error) => CallToolResult.failed(error)
      )
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "QR Code Server", version = "1.0.0"),
      tools = Some(ToolSet.of(generate)),
      resources = Some(
        UiResources.of[IO](UiResource(ResourceUri, "view", html, Some("View HTML resource with CSP metadata for external dependencies.")))
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("qr-server/view").map(apply)

object Main extends ExampleMain("qr-server", _ => QrServer.load)
