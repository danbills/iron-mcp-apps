package shadertoy

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Typed facade for ShaderToyLite.js (vendored with an esbuild alias). */
@js.native
@JSImport("shadertoy-lite", JSImport.Default)
class ShaderToyLite(@annotation.unused canvasId: String) extends js.Object:
  def setCommon(source: String): Unit = js.native
  def setBufferA(config: js.UndefOr[js.Object]): Unit = js.native
  def setBufferB(config: js.UndefOr[js.Object]): Unit = js.native
  def setBufferC(config: js.UndefOr[js.Object]): Unit = js.native
  def setBufferD(config: js.UndefOr[js.Object]): Unit = js.native
  def setImage(config: js.UndefOr[js.Object]): Unit = js.native
  def time(): Double = js.native
  def isPlaying(): Boolean = js.native
  def reset(): Unit = js.native
  def pause(): Unit = js.native
  def play(): Unit = js.native
