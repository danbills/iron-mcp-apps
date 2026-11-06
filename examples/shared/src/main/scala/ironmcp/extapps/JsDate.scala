package ironmcp.extapps

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, YearMonth, ZoneOffset}

/** Dates written the way upstream's JavaScript writes them, so results read the same. */
object JsDate:

  private val Iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  /** `Date.prototype.toISOString()`: always milliseconds, always UTC. */
  def iso(instant: Instant): String = Iso.format(instant)

  /** The month of `date.setMonth(date.getMonth() - n)`: the day carries over,
    * so the 31st of a 30-day month lands in the next one, as in JavaScript.
    */
  def monthsBack(today: LocalDate, n: Int): YearMonth =
    YearMonth.from(YearMonth.from(today).minusMonths(n.toLong).atDay(1).plusDays((today.getDayOfMonth - 1).toLong))
