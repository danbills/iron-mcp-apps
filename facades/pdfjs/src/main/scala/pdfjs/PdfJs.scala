package pdfjs

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait PDFPageProxy extends js.Object:
  def pageNumber: Int = js.native
  def getViewport(params: js.Dynamic): js.Dynamic = js.native
  def render(params: js.Dynamic): js.Dynamic = js.native
  def getTextContent(): js.Promise[js.Dynamic] = js.native

@js.native
trait PDFDocumentProxy extends js.Object:
  def numPages: Int = js.native
  def getPage(pageNumber: Int): js.Promise[PDFPageProxy] = js.native
  def destroy(): js.Promise[Unit] = js.native

@js.native
@JSImport("pdfjs-dist/build/pdf.mjs", JSImport.Namespace)
object PdfJs extends js.Object:
  val GlobalWorkerOptions: js.Dynamic = js.native
  def getDocument(src: js.Any): js.Dynamic = js.native
