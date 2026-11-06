package ironmcp.extapps.scenario

import cats.effect.IO
import chartjs.{Chart, ChartConfiguration, ChartData, ChartJs, Dataset, TooltipItem}
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Js, Ui}
import ironmcp.protocol.Implementation
import org.scalajs.dom

import scala.scalajs.js

/** Upstream's SaaS scenario modeler (React there): five sliders drive twelve
  * months of projections, recomputed in the View with the server's own
  * [[Calculations]], against a template chosen to compare with.
  */
object ScenarioView extends ExampleView:

  val appInfo: Implementation   = Implementation(name = "SaaS Scenario Modeler", version = "1.0.0")
  override def theming: Boolean = false

  private val Fallback = ScenarioInputs(50000, 5, 3, 80, 30000)

  private var templates: List[ScenarioTemplate] = Nil
  private var defaultInputs                     = Fallback
  private var inputs                            = Fallback
  private var selectedId: Option[String]        = None
  private var chart: Option[Chart]              = None

  // ------------------------------------------------------------ upstream's View formatters

  private def stripZeros(s: String) = s.replaceAll("\\.?0+$", "")

  private def currency(value: Double): String =
    val abs  = math.abs(value)
    val sign = if value < 0 then "-" else ""
    if abs >= 1000000 then s"$sign$$${stripZeros(Js.fixed(abs / 1000000, if abs >= 10000000 then 1 else 2))}M"
    else if abs >= 1000 then s"$sign$$${stripZeros(Js.fixed(abs / 1000, if abs >= 100000 then 0 else 1))}K"
    else s"$sign$$${Js.str(Js.round(abs))}"

  private def percent(value: Double, showSign: Boolean = false): String =
    s"${if showSign && value > 0 then "+" else ""}${Js.fixed(value, 1).replaceAll("\\.0$", "")}%"

  private def sliderCurrency(value: Double): String = if value >= 1000 then s"$$${Js.fixed(value / 1000, 0)}K" else s"$$${Js.str(value)}"

  // ------------------------------------------------------------ the page

  private final case class Slider(key: String, label: String, min: Double, max: Double, step: Double, format: Double => String,
      get: ScenarioInputs => Double, set: (ScenarioInputs, Double) => ScenarioInputs)

  private val Sliders = List(
    Slider("startingMRR", "Starting MRR", 10000, 500000, 5000, sliderCurrency, _.startingMRR, (i, v) => i.copy(startingMRR = v)),
    Slider("monthlyGrowthRate", "Growth Rate", 0, 20, 0.5, percent(_), _.monthlyGrowthRate, (i, v) => i.copy(monthlyGrowthRate = v)),
    Slider("monthlyChurnRate", "Churn Rate", 0, 15, 0.5, percent(_), _.monthlyChurnRate, (i, v) => i.copy(monthlyChurnRate = v)),
    Slider("grossMargin", "Gross Margin", 50, 95, 5, percent(_), _.grossMargin, (i, v) => i.copy(grossMargin = v)),
    Slider("fixedCosts", "Fixed Costs", 5000, 200000, 5000, sliderCurrency, _.fixedCosts, (i, v) => i.copy(fixedCosts = v))
  )

  private def skeleton: String =
    val sliders = Sliders.map { s =>
      s"""<div class="slider-row"><label class="slider-label">${s.label}</label><input type="range" class="slider-input" data-key="${s.key}" min="${Js.str(s.min)}" max="${Js.str(s.max)}" step="${Js.str(s.step)}" value="${Js.str(s.get(inputs))}"><span class="slider-value">${s.format(s.get(inputs))}</span></div>"""
    }.mkString
    s"""<main class="main"><header class="header"><h1 class="header-title">SaaS Scenario Modeler</h1><div class="header-controls"><select class="template-select"><option value="">Compare to...</option></select><button class="reset-button" id="reset">Reset</button></div></header>""" +
      s"""<section class="parameters-section"><h2 class="section-title">Parameters</h2>$sliders</section>""" +
      """<section class="chart-section"><h2 class="section-title">12-Month Projection</h2><div class="chart-container"><canvas></canvas></div><div class="chart-legend">""" +
      """<span class="legend-item"><span style="color: rgb(59, 130, 246);">--</span> MRR</span><span class="legend-item"><span style="color: rgb(16, 185, 129);">--</span> Gross Profit</span><span class="legend-item"><span style="color: rgb(245, 158, 11);">--</span> Net Profit</span>""" +
      """</div></section><section class="metrics-section"></section></main>"""

  private def selected: Option[ScenarioTemplate] = selectedId.flatMap(id => templates.find(_.id == id))

  private def card(label: String, value: String, variant: String = "default") =
    s"""<div class="metric-card metric-card--$variant"><span class="metric-value">$value</span><span class="metric-label">$label</span></div>"""

  private def renderMetrics(user: ScenarioSummary): Unit =
    val template = selected.map(t => (t.name, t.summary))
    val delta    = template.map((_, s) => (user.totalProfit - s.totalProfit) / math.abs(s.totalProfit) * 100)
    def sign(v: Double) = if v >= 0 then "positive" else "negative"
    val templateColumn = template.fold("") { (name, s) =>
      s"""<div class="metrics-column template"><h3>vs. $name</h3><div class="metrics-grid">${card("End MRR", currency(s.endingMRR))}${card("Total Profit", currency(s.totalProfit), sign(s.totalProfit))}</div></div>"""
    }
    val deltaItem = delta.fold("")(d => s"""<span class="summary-item">vs. template: <span class="summary-value ${sign(d)}">${percent(d, showSign = true)}</span></span>""")
    Dom.query[dom.HTMLElement](".metrics-section").innerHTML =
      s"""<div class="metrics-comparison"><div class="metrics-column"><h3>Your Scenario</h3><div class="metrics-grid">""" +
        card("End MRR", currency(user.endingMRR)) + card("Total Revenue", currency(user.totalRevenue)) +
        card("Total Profit", currency(user.totalProfit), sign(user.totalProfit)) +
        s"""</div></div>$templateColumn</div><div class="metrics-summary">""" +
        s"""<span class="summary-item">Break-even: <span class="summary-value">${user.breakEvenMonth.fold("Not achieved")(m => s"Month $m")}</span></span>""" +
        s"""<span class="summary-item">MRR Growth: <span class="summary-value ${sign(user.mrrGrowthPct)}">${percent(user.mrrGrowthPct, showSign = true)}</span></span>""" +
        deltaItem + "</div>"

  private def renderHeader(ui: Ui): Unit =
    val select = Dom.query[dom.HTMLSelectElement](".template-select")
    select.innerHTML = """<option value="">Compare to...</option>""" + templates.map(t => s"""<option value="${t.id}">${t.icon} ${t.name}</option>""").mkString
    select.value = selectedId.getOrElse("")
    Dom.queryAll[dom.HTMLElement]("#load").foreach(_.remove())
    if selected.isDefined then
      Dom.byId[dom.HTMLElement]("reset").insertAdjacentHTML("beforebegin", """<button class="reset-button" id="load">Load</button>""")
      ui.onClick(Dom.byId("load"))(IO(selected.foreach { t => inputs = t.parameters; selectedId = None; update(ui) }))
    val legend = Dom.query[dom.HTMLElement](".chart-legend")
    Dom.queryAll[dom.HTMLElement](".legend-template").foreach(_.remove())
    selected.foreach(t => legend.insertAdjacentHTML("beforeend", s"""<span class="legend-item legend-template">(dashed = ${t.name})</span>"""))

  /** React's re-render: the sliders, the header, the chart's data and the metrics. */
  private def update(ui: Ui): Unit =
    val (projections, summary) = Calculations.scenario(inputs)
    Dom.queryAll[dom.HTMLInputElement](".slider-input").zip(Sliders).foreach { (input, s) =>
      input.value = Js.str(s.get(inputs))
      input.nextElementSibling.textContent = s.format(s.get(inputs))
    }
    renderHeader(ui)
    chart.foreach { c =>
      def set(i: Int, values: List[Double]) = c.data.datasets(i).data = js.Array(values.map(v => v: js.Any)*)
      set(0, projections.map(_.mrr))
      set(1, projections.map(_.grossProfit))
      set(2, projections.map(_.netProfit))
      selected match
        case Some(t) =>
          set(3, t.projections.map(_.mrr))
          set(4, t.projections.map(_.grossProfit))
          set(5, t.projections.map(_.netProfit))
          (3 to 5).foreach(i => c.data.datasets(i).hidden = false)
        case None => (3 to 5).foreach(i => c.data.datasets(i).hidden = true)
      c.update("none")
    }
    renderMetrics(summary)

  // ------------------------------------------------------------ the chart

  private def dark = dom.window.matchMedia("(prefers-color-scheme: dark)").matches

  private def line(name: String, color: String, fillColor: Option[String], template: Boolean): Dataset =
    new Dataset:
      var data = js.Array[js.Any]()
      label = name
      borderColor = color
      fillColor.foreach(c => backgroundColor = c)
      if template then
        borderDash = js.Array(5.0, 5.0)
        hidden = true
      fill = false
      tension = 0.3
      pointRadius = 0
      borderWidth = if template then 1.5 else 2

  private def initChart(): Chart =
    val text = if dark then "#9ca3af" else "#6b7280"
    val grid = if dark then "#374151" else "#e5e7eb"
    val label: js.Function1[TooltipItem, String] = item =>
      val value = item.parsed.y.asInstanceOf[Double]
      val formatted = if math.abs(value) >= 1000 then s"$$${Js.fixed(value / 1000, 1)}K" else s"$$${Js.fixed(value, 0)}"
      s"${item.dataset.label.getOrElse("")}: $formatted"
    val tick: js.Function1[js.Any, String] = v =>
      val n = v.asInstanceOf[Double]
      if math.abs(n) >= 1000 then s"$$${Js.fixed(n / 1000, 0)}K" else s"$$${Js.str(n)}"
    val c = Chart(
      Dom.query[dom.HTMLCanvasElement](".chart-container canvas"),
      new ChartConfiguration:
        val `type` = "line"
        val data = new ChartData:
          labels = js.Array((1 to 12).map(i => s"M$i")*)
          var datasets = js.Array(
            line("MRR", "#3b82f6", Some("rgba(59, 130, 246, 0.1)"), template = false),
            line("Gross Profit", "#10b981", Some("rgba(16, 185, 129, 0.1)"), template = false),
            line("Net Profit", "#f59e0b", Some("rgba(245, 158, 11, 0.1)"), template = false),
            line("Template MRR", "#3b82f6", None, template = true),
            line("Template Gross", "#10b981", None, template = true),
            line("Template Net", "#f59e0b", None, template = true)
          )
        override val options = js.Dynamic.literal(
          responsive = true,
          maintainAspectRatio = false,
          interaction = js.Dynamic.literal(intersect = false, mode = "index"),
          plugins = js.Dynamic.literal(
            legend = js.Dynamic.literal(display = false),
            tooltip = js.Dynamic.literal(
              backgroundColor = if dark then "#1f2937" else "#ffffff",
              titleColor = if dark then "#f9fafb" else "#111827",
              bodyColor = if dark then "#9ca3af" else "#6b7280",
              borderColor = grid,
              borderWidth = 1,
              callbacks = js.Dynamic.literal(label = label)
            )
          ),
          scales = js.Dynamic.literal(
            y = js.Dynamic.literal(grid = js.Dynamic.literal(color = grid), ticks = js.Dynamic.literal(color = text, callback = tick)),
            x = js.Dynamic.literal(grid = js.Dynamic.literal(display = false), ticks = js.Dynamic.literal(color = text))
          )
        )
    )
    c

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    IO {
      Chart.register(ChartJs.registerables.toSeq*)
      Dom.byId[dom.HTMLElement]("root").innerHTML = skeleton
      // Created empty, then filled without animation, as upstream's effects do.
      chart = Some(initChart())
      update(ui)
      Dom.queryAll[dom.HTMLInputElement](".slider-input").zip(Sliders).foreach { (input, s) =>
        ui.on(input, "input")(_ => IO { inputs = s.set(inputs, input.value.toDouble); update(ui) })
      }
      val select = Dom.query[dom.HTMLSelectElement](".template-select")
      ui.on(select, "change")(_ => IO { selectedId = Option(select.value).filter(_.nonEmpty); update(ui) })
      ui.onClick(Dom.byId("reset"))(IO { inputs = defaultInputs; selectedId = None; update(ui) })
      dom.window
        .matchMedia("(prefers-color-scheme: dark)")
        .addEventListener("change", (_: dom.Event) => ui.run(IO { chart.foreach(_.destroy()); chart = Some(initChart()); update(ui) }))
    } >>
      SafeArea.follow(view, Dom.query[dom.HTMLElement]("main.main")).surround(
        view.events
          .evalMap {
            case HostEvent.ToolResult(result) =>
              IO(result.structuredAs[ScenarioData].foreach { data =>
                templates = data.templates
                defaultInputs = data.defaultInputs
                update(ui)
              })
            case _ => IO.unit
          }
          .compile
          .drain
      )
