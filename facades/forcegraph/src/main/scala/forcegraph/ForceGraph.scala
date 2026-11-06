package forcegraph

import org.scalajs.dom.{HTMLElement, MouseEvent}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait NodeObject extends js.Object:
  var id: js.UndefOr[String] = js.native
  var x: js.UndefOr[Double]  = js.native
  var y: js.UndefOr[Double]  = js.native
  var vx: js.UndefOr[Double] = js.native
  var vy: js.UndefOr[Double] = js.native

@js.native
trait LinkObject extends js.Object:
  var source: js.Any = js.native
  var target: js.Any = js.native

@js.native
@JSImport("force-graph", JSImport.Default)
class ForceGraph(@annotation.unused container: HTMLElement) extends js.Object:
  def nodeId(field: String): this.type = js.native
  def nodeLabel(field: String): this.type = js.native
  def nodeColor(fn: js.Function1[js.Dynamic, String]): this.type = js.native
  def nodeVal(value: Double): this.type = js.native
  def linkDirectionalArrowLength(length: Double): this.type = js.native
  def linkDirectionalArrowRelPos(pos: Double): this.type = js.native
  def linkColor(fn: js.Function1[js.Dynamic, String]): this.type = js.native
  def onNodeClick(cb: js.Function2[js.Dynamic, MouseEvent, Unit]): this.type = js.native
  def onBackgroundClick(cb: js.Function0[Unit]): this.type = js.native
  def d3Force(name: String, force: js.Any): this.type = js.native
  def d3VelocityDecay(decay: Double): this.type = js.native
  def cooldownTime(time: Double): this.type = js.native
  def d3AlphaMin(min: Double): this.type = js.native
  def graphData(data: js.Object): this.type = js.native
  def width(w: Double): this.type = js.native
  def height(h: Double): this.type = js.native
  def zoom(): Double = js.native
  def zoom(factor: Double, durationMs: Double = 0): this.type = js.native
  def centerAt(x: Double, y: Double, durationMs: Double = 0): this.type = js.native
  def warmupTicks(ticks: Int): this.type = js.native

@js.native
@JSImport("d3-force-3d", JSImport.Namespace)
object D3Force3D extends js.Object:
  def forceCenter(): js.Dynamic = js.native
  def forceCollide(radius: Double): js.Dynamic = js.native
  def forceLink(): js.Dynamic = js.native
  def forceManyBody(): js.Dynamic = js.native
