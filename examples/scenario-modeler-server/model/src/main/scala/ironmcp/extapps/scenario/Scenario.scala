package ironmcp.extapps.scenario

import io.circe.{Codec, Decoder}
import ironmcp.schema.JsonSchemaOf

final case class ScenarioInputs(startingMRR: Double, monthlyGrowthRate: Double, monthlyChurnRate: Double, grossMargin: Double, fixedCosts: Double)
    derives Codec.AsObject, JsonSchemaOf

final case class MonthlyProjection(month: Int, mrr: Double, grossProfit: Double, netProfit: Double, cumulativeRevenue: Double)
    derives Codec.AsObject, JsonSchemaOf

final case class ScenarioSummary(
    endingMRR: Double,
    arr: Double,
    totalRevenue: Double,
    totalProfit: Double,
    mrrGrowthPct: Double,
    avgMargin: Double,
    breakEvenMonth: Option[Int]
) derives Codec.AsObject, JsonSchemaOf

final case class ScenarioTemplate(
    id: String,
    name: String,
    description: String,
    icon: String,
    parameters: ScenarioInputs,
    projections: List[MonthlyProjection],
    summary: ScenarioSummary,
    keyInsight: String
) derives Codec.AsObject, JsonSchemaOf

final case class ScenarioData(
    templates: List[ScenarioTemplate],
    defaultInputs: ScenarioInputs,
    customProjections: Option[List[MonthlyProjection]] = None,
    customSummary: Option[ScenarioSummary] = None
) derives Codec.AsObject, JsonSchemaOf

final case class ScenarioArgs(customInputs: Option[ScenarioInputs] = None) derives Decoder, JsonSchemaOf

/** Twelve months of MRR at net growth (growth minus churn), and what they add
  * up to. The server computes the templates with it; the View recomputes as the
  * sliders move. The same code on both sides (JVM and Scala.js).
  */
object Calculations:

  def projections(in: ScenarioInputs): List[MonthlyProjection] =
    val net = (in.monthlyGrowthRate - in.monthlyChurnRate) / 100
    (1 to 12).toList
      .foldLeft((List.empty[MonthlyProjection], 0.0)) { case ((acc, cumulative), month) =>
        val mrr   = in.startingMRR * math.pow(1 + net, month.toDouble)
        val gross = mrr * (in.grossMargin / 100)
        (MonthlyProjection(month, mrr, gross, gross - in.fixedCosts, cumulative + mrr) :: acc, cumulative + mrr)
      }
      ._1
      .reverse

  def summary(ps: List[MonthlyProjection], in: ScenarioInputs): ScenarioSummary =
    val ending       = ps(11).mrr
    val totalRevenue = ps.foldLeft(0.0)(_ + _.mrr)
    val totalProfit  = ps.foldLeft(0.0)(_ + _.netProfit)
    ScenarioSummary(
      ending,
      ending * 12,
      totalRevenue,
      totalProfit,
      (ending - in.startingMRR) / in.startingMRR * 100,
      totalProfit / totalRevenue * 100,
      ps.find(_.netProfit >= 0).map(_.month)
    )

  def scenario(in: ScenarioInputs): (List[MonthlyProjection], ScenarioSummary) =
    val ps = projections(in)
    (ps, summary(ps, in))
