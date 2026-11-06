package ironmcp.extapps.qr

import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.BinaryBitmap
import io.github.iltotore.iron.*

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

class QrSuite extends munit.FunSuite:

  private def decode(base64: String) =
    val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder.decode(base64)))
    (image, new QRCodeReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))).getText)

  test("the defaults: upstream's URL, 10 px boxes, a 4-box border, black on white"):
    val Right(png) = QrServer.png(QrArgs()): @unchecked
    val (image, text) = decode(png)
    assertEquals(text, "https://modelcontextprotocol.io")
    assertEquals(image.getWidth % 10, 0)
    assertEquals(image.getRGB(0, 0) & 0xffffff, 0xffffff, "the border is the background")

  test("colours as Pillow names them, in any notation"):
    assertEquals(Colors.parse("red").map(_.getRGB & 0xffffff), Some(0xff0000))
    assertEquals(Colors.parse("#0F0").map(_.getRGB & 0xffffff), Some(0x00ff00))
    assertEquals(Colors.parse("rgb(0, 0, 255)").map(_.getRGB & 0xffffff), Some(0x0000ff))
    assertEquals(Colors.parse("RebeccaPurple").map(_.getRGB & 0xffffff), Some(0x663399))
    assertEquals(Colors.parse("not-a-colour"), None)

  test("an unknown colour is the tool's error, not a crash"):
    assert(QrServer.png(QrArgs(fill_color = Some("blurple".refineUnsafe))).left.exists(_.contains("blurple")))

  test("text round-trips through the code at every error-correction level"):
    for level <- List("L", "M", "Q", "H") do
      val Right(png) = QrServer.png(QrArgs(text = Some("héllo, QR".refineUnsafe), error_correction = Some(level.refineUnsafe))): @unchecked
      assertEquals(decode(png)._2, "héllo, QR")
