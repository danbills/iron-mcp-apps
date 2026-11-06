package ironmcp.extapps.cohort

import cats.Show
import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.all.*
import io.circe.Decoder
import io.circe.derivation.{Configuration, ConfiguredEnumCodec}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, JsDate, JsNumber, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.{ConfiguredEnumJsonSchemaOf, Default, JsonSchemaOf}
import ironmcp.server.*

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

given Configuration = Configuration.default

enum Metric derives ConfiguredEnumCodec, ConfiguredEnumJsonSchemaOf:
  case retention, revenue, active

enum PeriodType derives ConfiguredEnumCodec, ConfiguredEnumJsonSchemaOf:
  case monthly, weekly

final case class CohortArgs(
    metric: Option[Metric :| Default["retention"]] = None,
    periodType: Option[PeriodType :| Default["monthly"]] = None,
    cohortCount: Option[Double :| (Interval.Closed[3d, 24d] & Default[12])] = None,
    maxPeriods: Option[Double :| (Interval.Closed[3d, 24d] & Default[12])] = None
) derives Decoder, JsonSchemaOf

/** Upstream's cohort-heatmap-server: random retention cohorts, decaying from 100%. */
object CohortServer:

  val ResourceUri: UiUri = "ui://get-cohort-data/mcp-app.html"

  private final case class Params(base: Double, decay: Double, floor: Double, noise: Double)
  private val ParamsFor = Map(
    Metric.retention -> Params(0.75, 0.12, 0.08, 0.04),
    Metric.revenue   -> Params(0.7, 0.1, 0.15, 0.06),
    Metric.active    -> Params(0.6, 0.18, 0.05, 0.05)
  )

  private val Label = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

  def generate(random: Random[IO], metric: Metric, periodType: PeriodType, cohortCount: Int, maxPeriods: Int): IO[CohortData] =
    val params = ParamsFor(metric)
    def retention(period: Int): IO[Double] =
      if period == 0 then IO.pure(1.0)
      else
        random.nextDouble.map { r =>
          val base = params.base * math.exp(-params.decay * (period - 1)) + params.floor
          math.max(0, math.min(1, base + (r - 0.5) * 2 * params.noise))
        }
    for
      today <- IO(LocalDate.now())
      cohorts <- (0 until cohortCount).toList.traverse { c =>
                   val ym = JsDate.monthsBack(today, cohortCount - 1 - c)
                   for
                     users <- random.nextDouble.map(r => math.floor(1000 + r * 4000).toInt)
                     cells <- (0 until math.min(cohortCount - c, maxPeriods)).toList.foldLeftM((List.empty[CohortCell], 1.0)) {
                                case ((cells, previous), p) =>
                                  retention(p).map { r =>
                                    val capped = math.min(r, previous + 0.02)
                                    (CohortCell(c, p, capped, math.round(users * capped).toInt, users) :: cells, capped)
                                  }
                              }
                   yield CohortRow(f"${ym.getYear}%d-${ym.getMonthValue}%02d", Label.format(ym.atDay(1)), users, cells._1.reverse)
                 }
      now <- IO.realTimeInstant
    yield CohortData(
      cohorts,
      (0 until maxPeriods).map(i => s"M$i").toList,
      (0 until maxPeriods).map(i => s"Month $i").toList,
      metric.toString,
      periodType.toString,
      JsDate.iso(now)
    )

  given Show[CohortData] = data =>
    val later = data.cohorts.flatMap(_.cells).filter(_.periodIndex > 0)
    val avg   = later.foldLeft(0.0)((sum, cell) => sum + cell.retention / later.size)
    s"""Cohort Analysis: ${data.cohorts.size} cohorts, ${data.periods.size} periods
       |Average retention: ${JsNumber.fixed(avg * 100, 1)}%
       |Metric: ${data.metric}, Period: ${data.periodType}""".stripMargin

  def apply(html: String, random: Random[IO]): McpServer[IO] =
    val tool = McpTool[CohortArgs](
      "get-cohort-data",
      "Returns cohort retention heatmap data showing customer retention over time by signup month",
      title = Some("Get Cohort Retention Data")
    )(args =>
      generate(
        random,
        args.metric.fold(Metric.retention)(m => m),
        args.periodType.fold(PeriodType.monthly)(p => p),
        args.cohortCount.fold(12)(n => n.toInt),
        args.maxPeriods.fold(12)(n => n.toInt)
      )
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Cohort Heatmap Server", version = "1.0.0"),
      tools = Some(ToolSet.of(tool)),
      resources = Some(UiResources.of[IO](UiResource(ResourceUri, "ui://get-cohort-data/mcp-app.html", html))),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = (ViewPage.load("cohort-heatmap-server/view"), Random.scalaUtilRandom[IO]).mapN(apply)

object Main extends ExampleMain("cohort-heatmap-server", _ => CohortServer.load)
