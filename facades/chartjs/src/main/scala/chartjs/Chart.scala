package chartjs

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Chart.js 4 (`chart.js` on npm), as far as the Views use it: a chart made
  * from a configuration, whose data and options can change in place before
  * `update`. Option bags stay `js.Object`s (build them with
  * `js.Dynamic.literal`): Chart.js reads a few hundred optional keys, and the
  * Views set a handful.
  */
@js.native
@JSImport("chart.js", "Chart")
class Chart(@annotation.unused canvas: dom.HTMLCanvasElement, @annotation.unused config: ChartConfiguration) extends js.Object:
  val data: ChartData       = js.native
  val options: js.Dynamic   = js.native
  def update(): Unit        = js.native
  /** `"none"` skips the animation. */
  def update(mode: String): Unit = js.native
  def destroy(): Unit       = js.native

@js.native
@JSImport("chart.js", "Chart")
object Chart extends js.Object:
  /** Registers controllers, elements, scales and plugins; `Chart.register(ChartJs.registerables.toSeq*)` registers all. */
  def register(items: js.Any*): Unit = js.native

@js.native
@JSImport("chart.js", JSImport.Namespace)
object ChartJs extends js.Object:
  val registerables: js.Array[js.Any] = js.native

trait ChartConfiguration extends js.Object:
  val `type`: String
  val data: ChartData
  val options: js.UndefOr[js.Any] = js.undefined

trait ChartData extends js.Object:
  var labels: js.UndefOr[js.Array[String]] = js.undefined
  var datasets: js.Array[Dataset]

/** One dataset. Its colours may be one value or one per point. */
trait Dataset extends js.Object:
  var data: js.Array[js.Any]
  var label: js.UndefOr[String]                = js.undefined
  var hidden: js.UndefOr[Boolean]              = js.undefined
  var backgroundColor: js.UndefOr[js.Any]      = js.undefined
  var borderColor: js.UndefOr[js.Any]          = js.undefined
  var borderWidth: js.UndefOr[Double]          = js.undefined
  var borderDash: js.UndefOr[js.Array[Double]] = js.undefined
  var hoverBackgroundColor: js.UndefOr[js.Any] = js.undefined
  var hoverBorderColor: js.UndefOr[js.Any]     = js.undefined
  var hoverBorderWidth: js.UndefOr[Double]     = js.undefined
  var tension: js.UndefOr[Double]              = js.undefined
  var pointRadius: js.UndefOr[Double]          = js.undefined
  var fill: js.UndefOr[js.Any]                 = js.undefined

/** What a tooltip callback gets. `parsed` is a number for a doughnut, `{x, y}` for a line or bubble. */
@js.native
trait TooltipItem extends js.Object:
  val label: String         = js.native
  val parsed: js.Dynamic    = js.native
  val raw: js.Dynamic       = js.native
  val dataset: Dataset      = js.native
  val dataIndex: Int        = js.native
  val datasetIndex: Int     = js.native

/** What `onClick` / `onHover` get for each element under the pointer. */
@js.native
trait ActiveElement extends js.Object:
  val index: Int        = js.native
  val datasetIndex: Int = js.native
