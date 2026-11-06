package ironmcp.extapps.scenario

import cats.Show
import cats.effect.IO
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, JsNumber, ViewPage}
import ironmcp.protocol.*
import ironmcp.server.*

/** Upstream's scenario-modeler-server: five SaaS templates and, on request, a custom scenario. */
object ScenarioServer:

  val ResourceUri: UiUri = "ui://scenario-modeler/mcp-app.html"

  private def template(id: String, name: String, description: String, icon: String, in: ScenarioInputs, insight: String) =
    val (ps, summary) = Calculations.scenario(in)
    ScenarioTemplate(id, name, description, icon, in, ps, summary, insight)

  val Templates: List[ScenarioTemplate] = List(
    template("bootstrapped", "Bootstrapped Growth", "Low burn, steady growth, path to profitability", "🌱", ScenarioInputs(30000, 4, 2, 85, 20000), "Profitable by month 1, but slower scale"),
    template("vc-rocketship", "VC Rocketship", "High burn, explosive growth, raise more later", "🚀", ScenarioInputs(100000, 15, 5, 70, 150000), "Loses money early but ends at 3x MRR"),
    template("cash-cow", "Cash Cow", "Mature product, high margin, stable revenue", "🐄", ScenarioInputs(80000, 2, 1, 90, 40000), "Consistent profitability, low risk"),
    template("turnaround", "Turnaround", "Fighting churn, rebuilding product-market fit", "🔄", ScenarioInputs(60000, 6, 8, 75, 50000), "Negative net growth requires urgent action"),
    template("efficient-growth", "Efficient Growth", "Balanced approach with sustainable economics", "⚖️", ScenarioInputs(50000, 8, 3, 80, 35000), "Good growth with path to profitability")
  )

  val DefaultInputs: ScenarioInputs = ScenarioInputs(50000, 5, 3, 80, 30000)

  /** Upstream server's formatter (the View has its own). */
  private def currency(value: Double): String =
    val abs  = math.abs(value)
    val sign = if value < 0 then "-" else ""
    val dollar = "$"
    if abs >= 1000000 then s"$sign$dollar${JsNumber.fixed(abs / 1000000, 2)}M"
    else if abs >= 1000 then s"$sign$dollar${JsNumber.fixed(abs / 1000, 1)}K"
    else s"$sign$dollar${math.round(abs)}"

  given Show[ScenarioData] = data =>
    val custom = data.customSummary.fold("Use customInputs parameter to compute projections for a specific scenario.") { s =>
      List(
        "Custom Scenario:",
        s"  Ending MRR: ${currency(s.endingMRR)}",
        s"  ARR: ${currency(s.arr)}",
        s"  Total Revenue: ${currency(s.totalRevenue)}",
        s"  Total Profit: ${currency(s.totalProfit)}",
        s"  MRR Growth: ${JsNumber.fixed(s.mrrGrowthPct, 1)}%",
        s"  Break-even: ${s.breakEvenMonth.fold("Not achieved")(m => s"Month $m")}"
      ).mkString("\n")
    }
    (List("SaaS Scenario Modeler", "=".repeat(40), "", "Available Templates:") ++
      data.templates.map(t => s"  ${t.icon} ${t.name}: ${t.description}") ++
      List("", custom)).mkString("\n")

  def apply(html: String): McpServer[IO] =
    val tool = McpTool[ScenarioArgs](
      "get-scenario-data",
      "Returns SaaS scenario templates and optionally computes custom projections for given inputs",
      title = Some("Get Scenario Data")
    )(args =>
      IO {
        val custom = args.customInputs.map(Calculations.scenario)
        ScenarioData(Templates, DefaultInputs, custom.map(_._1), custom.map(_._2))
      }
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "SaaS Scenario Modeler", version = "1.0.0"),
      tools = Some(ToolSet.of(tool)),
      resources = Some(UiResources.of[IO](UiResource(ResourceUri, "ui://scenario-modeler/mcp-app.html", html, Some("SaaS Scenario Modeler UI")))),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("scenario-modeler-server/view").map(apply)

object Main extends ExampleMain("scenario-modeler-server", _ => ScenarioServer.load)
