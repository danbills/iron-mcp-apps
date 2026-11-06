package ironmcp.extapps

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

/** Dates written the way upstream's JavaScript writes them, so results read the same. */
object JsDate:

  private val Iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  /** `Date.prototype.toISOString()`: always milliseconds, always UTC. */
  def iso(instant: Instant): String = Iso.format(instant)
