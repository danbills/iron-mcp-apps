package ironmcp.extapps

import java.text.NumberFormat
import java.util.Locale

/** Numbers written the way upstream's JavaScript writes them. */
object JsNumber:

  /** `String(n)`: `25`, not `25.0`. */
  def str(n: Double): String = if n.isWhole && math.abs(n) < 1e21 then n.toLong.toString else n.toString

  /** `n.toLocaleString()` in en-US, Node's default: `100,000`, at most three decimals. */
  def locale(n: Double): String =
    val format = NumberFormat.getNumberInstance(Locale.US)
    format.setMaximumFractionDigits(3)
    format.format(n)
