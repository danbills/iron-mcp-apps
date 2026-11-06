package ironmcp.extapps.budget

import cats.Show
import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, JsDate, JsNumber, NoArgs, ViewPage}
import ironmcp.protocol.*
import ironmcp.server.*

import java.time.LocalDate
import scala.collection.immutable.ListMap

/** Upstream's budget-allocator-server: budget configuration, 24 months of
  * allocation history and benchmarks by company stage. The View holds the
  * state and offers the model tools to read and change it.
  */
object BudgetServer:

  val ResourceUri: UiUri = "ui://budget-allocator/mcp-app.html"

  private final case class Category(id: String, name: String, color: String, defaultPercent: Double, trendPerMonth: Double)

  private val Categories = List(
    Category("marketing", "Marketing", "#3b82f6", 25, 0.15),
    Category("engineering", "Engineering", "#10b981", 35, -0.1),
    Category("operations", "Operations", "#f59e0b", 15, 0.05),
    Category("sales", "Sales", "#ef4444", 15, 0.08),
    Category("rd", "R&D", "#8b5cf6", 10, -0.18)
  )

  private def stage(name: String, rows: (String, (Double, Double, Double))*): StageBenchmark =
    StageBenchmark(name, ListMap.from(rows.map((id, p) => id -> BenchmarkPercentiles(p._1, p._2, p._3))))

  val Benchmarks: List[StageBenchmark] = List(
    stage("Seed", "marketing" -> (15, 20, 25), "engineering" -> (40, 47, 55), "operations" -> (8, 12, 15), "sales" -> (10, 15, 20), "rd" -> (5, 10, 15)),
    stage("Series A", "marketing" -> (20, 25, 30), "engineering" -> (35, 40, 45), "operations" -> (10, 14, 18), "sales" -> (15, 20, 25), "rd" -> (8, 12, 15)),
    stage("Series B", "marketing" -> (22, 27, 32), "engineering" -> (30, 35, 40), "operations" -> (12, 16, 20), "sales" -> (18, 23, 28), "rd" -> (8, 12, 15)),
    stage("Growth", "marketing" -> (25, 30, 35), "engineering" -> (25, 30, 35), "operations" -> (15, 18, 22), "sales" -> (20, 25, 30), "rd" -> (5, 8, 12))
  )

  /** JavaScript's `x & 0x7fffffff` on a double: ToInt32 (truncate, then
    * modulo 2^32), then the mask. The product below passes 2^53, so it is
    * rounded as a double first, exactly as upstream's `seed * 1103515245` is.
    */
  private def toInt32(d: Double): Int =
    val truncated = if d < 0 then math.ceil(d) else math.floor(d)
    (truncated % 4294967296.0).toLong.toInt

  /** Upstream's seeded LCG, number for number. */
  def seededRandom(seed: Int): () => Double =
    var state = seed.toDouble
    () =>
      state = (toInt32(state * 1103515245.0 + 12345.0) & 0x7fffffff).toDouble
      state / 0x7fffffff.toDouble

  /** 24 months of history, oldest first: default plus trend plus noise, normalised to 100%. */
  def history(today: LocalDate): List[HistoricalMonth] =
    val random = seededRandom(42)
    (23 to 0 by -1).toList.map { i =>
      val ym  = JsDate.monthsBack(today, i)
      val raw = Categories.map { c =>
        val trend = (23 - i) * c.trendPerMonth
        val noise = (random() - 0.5) * 3
        c.id -> math.max(0, math.min(100, c.defaultPercent + trend + noise))
      }
      val total = raw.foldLeft(0.0)(_ + _._2)
      HistoricalMonth(f"${ym.getYear}%d-${ym.getMonthValue}%02d", ListMap.from(raw.map((id, v) => id -> math.round((v / total) * 1000).toDouble / 10)))
    }

  def data(today: LocalDate): BudgetData =
    BudgetData(
      BudgetConfig(
        Categories.map(c => BudgetCategory(c.id, c.name, c.color, c.defaultPercent)),
        List(50000, 100000, 250000, 500000),
        100000,
        "USD",
        "$"
      ),
      BudgetAnalytics(history(today), Benchmarks, List("Seed", "Series A", "Series B", "Growth"), "Series A")
    )

  given Show[BudgetData] = data =>
    val symbol = data.config.currencySymbol
    List(
      "Budget Allocator Configuration",
      "==============================",
      "",
      s"Default Budget: $symbol${JsNumber.locale(data.config.defaultBudget)}",
      s"Available Presets: ${data.config.presetBudgets.map(b => s"$symbol${JsNumber.locale(b)}").mkString(", ")}",
      "",
      "Categories:"
    ).++(data.config.categories.map(c => s"  - ${c.name}: ${JsNumber.str(c.defaultPercent)}% default"))
      .++(
        List(
          "",
          s"Historical Data: ${data.analytics.history.size} months",
          s"Benchmark Stages: ${data.analytics.stages.mkString(", ")}",
          s"Default Stage: ${data.analytics.defaultStage}"
        )
      )
      .mkString("\n")

  def apply(html: String): McpServer[IO] =
    val getBudgetData = McpTool[NoArgs](
      "get-budget-data",
      "Returns budget configuration with 24 months of historical allocations and industry benchmarks by company stage. The widget is interactive and exposes tools for reading/modifying allocations, adjusting budgets, and comparing against industry benchmarks.",
      title = Some("Get Budget Data")
    )(_ => IO(data(LocalDate.now()))).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Budget Allocator Server", version = "1.0.0"),
      tools = Some(ToolSet.of(getBudgetData)),
      resources = Some(
        UiResources.of[IO](UiResource(ResourceUri, "ui://budget-allocator/mcp-app.html", html, Some("Interactive Budget Allocator UI")))
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("budget-allocator-server/view").map(apply)

object Main extends ExampleMain("budget-allocator-server", _ => BudgetServer.load)
