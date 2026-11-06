package ironmcp.extapps.segmentation

import io.circe.Codec
import ironmcp.schema.JsonSchemaOf

final case class Customer(
    id: String,
    name: String,
    segment: String,
    annualRevenue: Long,
    employeeCount: Long,
    accountAge: Long,
    engagementScore: Long,
    supportTickets: Long,
    nps: Long
) derives Codec.AsObject, JsonSchemaOf:
  /** One of [[Metrics.all]], by name. */
  def metric(name: String): Long = name match
    case "annualRevenue"   => annualRevenue
    case "employeeCount"   => employeeCount
    case "accountAge"      => accountAge
    case "engagementScore" => engagementScore
    case "supportTickets"  => supportTickets
    case _                 => nps

final case class SegmentSummary(name: String, count: Int, color: String) derives Codec.AsObject, JsonSchemaOf

final case class CustomerData(customers: List[Customer], segments: List[SegmentSummary]) derives Codec.AsObject, JsonSchemaOf

object Segments:
  val all: List[String] = List("Enterprise", "Mid-Market", "SMB", "Startup")
  val colors: Map[String, String] = Map("Enterprise" -> "#1e40af", "Mid-Market" -> "#0d9488", "SMB" -> "#059669", "Startup" -> "#6366f1")

object Metrics:
  val all: List[String] = List("annualRevenue", "employeeCount", "accountAge", "engagementScore", "supportTickets", "nps")
  val labels: Map[String, String] = Map(
    "annualRevenue"   -> "Annual Revenue",
    "employeeCount"   -> "Employees",
    "accountAge"      -> "Account Age (mo)",
    "engagementScore" -> "Engagement",
    "supportTickets"  -> "Support Tickets",
    "nps"             -> "NPS"
  )
