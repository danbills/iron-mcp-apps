package abcjs

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** abcjs 6 (`abcjs` on npm), as far as the sheet-music View uses it: render
  * ABC notation to SVG, and play it with abcjs's synth.
  */
@js.native
@JSImport("abcjs", JSImport.Default)
object ABCJS extends js.Object:
  /** Renders `abc` into `target`; one tune object per tune in it. */
  def renderAbc(target: dom.HTMLElement, abc: String, params: js.Object): js.Array[TuneObject] = js.native
  val synth: Synth = js.native

/** A parsed and rendered tune; opaque here, handed back to the synth. */
@js.native
trait TuneObject extends js.Object

@js.native
trait Synth extends js.Object:
  def supportsAudio(): Boolean = js.native
  /** The `SynthController` class: build one with [[SynthController.apply]]. */
  val SynthController: js.Dynamic = js.native

/** Play/loop/progress controls bound to a tune. */
@js.native
trait SynthController extends js.Object:
  def load(target: dom.HTMLElement, cursorControl: js.Any, options: js.Object): Unit = js.native
  def setTune(tune: TuneObject, userAction: Boolean, audioParams: js.Object): js.Promise[js.Any] = js.native

object SynthController:
  def apply(): SynthController = js.Dynamic.newInstance(ABCJS.synth.SynthController)().asInstanceOf[SynthController]
