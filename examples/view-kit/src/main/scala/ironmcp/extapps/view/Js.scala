package ironmcp.extapps.view

import scala.scalajs.js

/** Numbers written the way upstream's JavaScript writes them. */
object Js:

  /** `n.toFixed(digits)`: JavaScript's rounding, not `String.format`'s. */
  def fixed(n: Double, digits: Int): String = n.asInstanceOf[js.Dynamic].toFixed(digits).asInstanceOf[String]

  /** `n.toLocaleString()` in the page's locale: `100,000` in en-US. */
  def locale(n: Double): String = n.asInstanceOf[js.Dynamic].toLocaleString().asInstanceOf[String]

  /** `Math.round`: halves round up, toward +∞. */
  def round(n: Double): Double = js.Math.round(n)

  /** `String(n)`: `25`, not `25.0`. */
  def str(n: Double): String = n.toString
