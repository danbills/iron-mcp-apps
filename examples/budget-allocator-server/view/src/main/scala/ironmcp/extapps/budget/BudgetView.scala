package ironmcp.extapps.budget

import cats.effect.IO
import cats.syntax.all.*
import chartjs.{ActiveElement, Chart, ChartConfiguration, ChartData, ChartJs, Dataset, TooltipItem}
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, Json, Printer}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Js, Log, Ui}
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import org.scalajs.dom

import scala.scalajs.js

// The View's tools: their arguments (the input schema) and results.
final case class NoArgs() derives Decoder, JsonSchemaOf
final case class SetAllocation(
    categoryId: String :| DescribedAs[True, "Category ID (e.g., 'marketing', 'engineering', 'operations', 'sales', 'rd')"],
    percent: Double :| (Interval.Closed[0d, 100d] DescribedAs "Allocation percentage (0-100)")
) derives Decoder, JsonSchemaOf
final case class SetTotalBudget(amount: Double :| (Positive DescribedAs "Total budget amount")) derives Decoder, JsonSchemaOf
final case class SetStage(stage: String :| DescribedAs[True, "Company stage ID"]) derives Decoder, JsonSchemaOf

final case class AllocationAmount(percent: Double, amount: Double) derives Codec.AsObject, JsonSchemaOf
final case class CategoryRef(id: String, name: String, color: String) derives Codec.AsObject, JsonSchemaOf
final case class Allocations(
    totalBudget: Double,
    currency: String,
    currencySymbol: String,
    selectedStage: String,
    allocations: Map[String, AllocationAmount],
    categories: List[CategoryRef]
) derives Codec.AsObject, JsonSchemaOf
final case class Comparison(current: Double, p25: Double, p50: Double, p75: Double, status: String) derives Codec.AsObject, JsonSchemaOf
final case class BenchmarkComparison(stage: String, comparison: Map[String, Comparison]) derives Codec.AsObject, JsonSchemaOf

/** Upstream's budget allocator: a doughnut of the allocations, a slider (with
  * a sparkline of its history and a benchmark percentile) per category, and
  * tools for the model to read and change all of it.
  */
object BudgetView extends ExampleView:

  val appInfo: Implementation   = Implementation(name = "Budget Allocator", version = "1.0.0")
  override def theming: Boolean = false

  private val log = Log("[APP]")

  // State: one View, one JavaScript thread; changed only inside IO.
  private var config: Option[BudgetConfig]       = None
  private var analytics: Option[BudgetAnalytics] = None
  private var totalBudget: Double                = 100000
  private var allocations                        = scala.collection.mutable.LinkedHashMap.empty[String, Double]
  private var selectedStage                      = "Series A"
  private var chart: Option[Chart]               = None

  // ------------------------------------------------------------ formatting

  private def symbol = config.fold("$")(_.currencySymbol)
  private def formatCurrency(amount: Double): String =
    if amount >= 1000 then s"$symbol${Js.str(Js.round(amount / 1000))}K" else s"$symbol${Js.locale(amount)}"
  private def formatCurrencyFull(amount: Double): String = s"$symbol${Js.locale(amount)}"

  // ------------------------------------------------------------ sparkline

  private def drawSparkline(canvas: dom.HTMLCanvasElement, data: List[Double], color: String): Unit =
    val ctx = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
    if ctx != null && data.size >= 2 then
      val width   = canvas.width.toDouble
      val height  = canvas.height.toDouble
      val padding = 2.0
      ctx.clearRect(0, 0, width, height)
      val min   = data.min - 2
      val max   = data.max + 2
      val range = if max - min == 0 then 1 else max - min
      def x(i: Int)         = padding + (i.toDouble / (data.size - 1)) * (width - 2 * padding)
      def y(value: Double)  = height - padding - ((value - min) / range) * (height - 2 * padding)
      ctx.beginPath()
      ctx.moveTo(padding, height - padding)
      data.zipWithIndex.foreach((v, i) => ctx.lineTo(x(i), y(v)))
      ctx.lineTo(width - padding, height - padding)
      ctx.closePath()
      ctx.fillStyle = s"${color}20"
      ctx.fill()
      ctx.beginPath()
      data.zipWithIndex.foreach((v, i) => if i == 0 then ctx.moveTo(x(i), y(v)) else ctx.lineTo(x(i), y(v)))
      ctx.strokeStyle = color
      ctx.lineWidth = 1.5
      ctx.stroke()

  // ------------------------------------------------------------ percentiles

  private def percentile(value: Double, b: BenchmarkPercentiles): Double =
    if value <= b.p25 then 25 * (value / b.p25)
    else if value <= b.p50 then 25 + 25 * ((value - b.p25) / (b.p50 - b.p25))
    else if value <= b.p75 then 50 + 25 * ((value - b.p50) / (b.p75 - b.p50))
    else 75 + 25 * math.min(1, (value - b.p75) / (b.p75 - b.p50))

  private def percentileClass(p: Double): String =
    if p >= 40 && p <= 60 then "percentile-normal" else if p > 60 then "percentile-high" else "percentile-low"

  // ------------------------------------------------------------ chart

  private def isDark = dom.window.matchMedia("(prefers-color-scheme: dark)").matches

  private def initChart(categories: List[BudgetCategory]): Chart =
    val canvas = Dom.byId[dom.HTMLCanvasElement]("budget-chart")
    val label: js.Function1[TooltipItem, String] = item =>
      val pct = item.parsed.asInstanceOf[Double]
      s"${item.label}: ${Js.fixed(pct, 1)}% (${formatCurrency(pct / 100 * totalBudget)})"
    val onClick: js.Function2[js.Any, js.Array[ActiveElement], Unit] = (_, elements) =>
      config.foreach(c => if elements.nonEmpty then focusSlider(c.categories(elements(0).index).id))
    val onHover: js.Function2[js.Any, js.Array[ActiveElement], Unit] = (_, elements) =>
      config.foreach(c => if elements.nonEmpty then highlightSlider(c.categories(elements(0).index).id) else clearSliderHighlight())
    Chart(
      canvas,
      new ChartConfiguration:
        val `type` = "doughnut"
        val data = new ChartData:
          labels = js.Array(categories.map(_.name)*)
          var datasets = js.Array(new Dataset:
            var data = js.Array(categories.map(c => allocations.getOrElse(c.id, c.defaultPercent): js.Any)*)
            backgroundColor = js.Array(categories.map(_.color)*)
            borderWidth = 2
            borderColor = if isDark then "#1f2937" else "#ffffff"
          )
        override val options = js.Dynamic.literal(
          responsive = true,
          maintainAspectRatio = true,
          cutout = "60%",
          plugins = js.Dynamic.literal(legend = js.Dynamic.literal(display = false), tooltip = js.Dynamic.literal(callbacks = js.Dynamic.literal(label = label))),
          onClick = onClick,
          onHover = onHover
        )
    )

  private def updateChart(): Unit =
    for c <- chart; cfg <- config do
      c.data.datasets(0).data = js.Array(cfg.categories.map(cat => allocations.getOrElse(cat.id, 0.0): js.Any)*)
      c.update("none")

  // ------------------------------------------------------------ sliders

  private def row(id: String): Option[dom.Element] = Option(dom.document.querySelector(s""".slider-row[data-category-id="$id"]"""))

  private def sliderRow(category: BudgetCategory, history: List[Double], ui: Ui): dom.HTMLElement =
    val allocation = allocations.getOrElse(category.id, category.defaultPercent)
    val amount     = allocation / 100 * totalBudget
    val first      = history.headOption.getOrElse(0.0)
    val last       = history.lastOption.getOrElse(0.0)
    val diff       = last - first
    val arrow      = if math.abs(diff) < 0.5 then "" else if diff > 0 then " +" else " "
    val tooltip    = s"Past allocations: ${Js.fixed(first, 0)}%$arrow${Js.fixed(diff, 1)}%"
    val row        = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
    row.className = "slider-row"
    row.setAttribute("data-category-id", category.id)
    row.innerHTML = s"""
    <label class="slider-label" style="--category-color: ${category.color}">
      <span class="color-dot"></span>
      <span class="label-text">${category.name.replace("&", "&amp;")}</span>
    </label>
    <div class="sparkline-wrapper">
      <canvas class="sparkline" width="50" height="28"></canvas>
      <span class="sparkline-tooltip">$tooltip</span>
    </div>
    <div class="slider-container">
      <input
        type="range"
        class="slider"
        min="0"
        max="100"
        step="1"
        value="${Js.str(allocation)}"
      />
    </div>
    <span class="slider-value">
      <span class="percent">${Js.fixed(allocation, 1)}%</span>
      <span class="amount">${formatCurrency(amount)}</span>
    </span>
    <span class="percentile-badge"></span>
  """
    drawSparkline(row.querySelector(".sparkline").asInstanceOf[dom.HTMLCanvasElement], history, category.color)
    val slider = row.querySelector(".slider").asInstanceOf[dom.HTMLInputElement]
    ui.on(slider, "input")(_ => IO(handleSliderChange(category.id, slider.value.toDouble)))
    row

  private def handleSliderChange(id: String, percent: Double): Unit =
    allocations(id) = percent
    updateSliderDisplay(id, percent)
    updateChart()
    updateStatusBar()
    updatePercentileBadge(id)
    updateComparisonSummary()

  private def updateSliderDisplay(id: String, percent: Double): Unit =
    row(id).foreach { r =>
      r.querySelector(".percent").textContent = s"${Js.fixed(percent, 1)}%"
      r.querySelector(".amount").textContent = formatCurrency(percent / 100 * totalBudget)
    }

  private def updateAllSliderAmounts(): Unit =
    config.foreach(_.categories.foreach(c => updateSliderDisplay(c.id, allocations.getOrElse(c.id, 0.0))))

  private def focusSlider(id: String): Unit =
    Option(dom.document.querySelector(s""".slider-row[data-category-id="$id"] .slider""")).foreach { s =>
      s.asInstanceOf[dom.HTMLElement].focus()
      highlightSlider(id)
    }

  private def highlightSlider(id: String): Unit =
    clearSliderHighlight()
    row(id).foreach(_.classList.add("highlighted"))

  private def clearSliderHighlight(): Unit = Dom.queryAll[dom.Element](".slider-row.highlighted").foreach(_.classList.remove("highlighted"))

  // ------------------------------------------------------------ badges, status, summary

  private def stageBenchmark: Option[StageBenchmark] = analytics.flatMap(_.benchmarks.find(_.stage == selectedStage))

  private def updatePercentileBadge(id: String): Unit =
    for
      b        <- stageBenchmark
      cfg      <- config
      category <- cfg.categories.find(_.id == id)
      bench    <- b.categoryBenchmarks.get(id)
      r        <- row(id)
    do
      val p     = percentile(allocations.getOrElse(id, category.defaultPercent), bench)
      val badge = r.querySelector(".percentile-badge")
      badge.setAttribute("class", s"percentile-badge ${percentileClass(p)}")
      badge.innerHTML = s"""<span class="percentile-icon"></span>${Js.str(Js.round(p))}th"""

  private def updateAllPercentileBadges(): Unit = config.foreach(_.categories.foreach(c => updatePercentileBadge(c.id)))

  private def updateStatusBar(): Unit =
    val total     = allocations.values.foldLeft(0.0)(_ + _)
    val allocated = total / 100 * totalBudget
    val (icon, cls) =
      if math.abs(total - 100) < 0.1 then ("", "status-balanced")
      else if total > 100 then (" Over", "status-warning status-over")
      else (" Under", "status-warning status-under")
    val bar = Dom.byId[dom.HTMLElement]("status-bar")
    bar.innerHTML = s"""
    Allocated: ${formatCurrencyFull(allocated)} / ${formatCurrencyFull(totalBudget)}
    <span class="status-icon">$icon</span>
  """
    bar.className = s"status-bar $cls"

  private def updateComparisonSummary(): Unit =
    for b <- stageBenchmark; cfg <- config do
      val deviations = cfg.categories.flatMap(c => b.categoryBenchmarks.get(c.id).map(bench => c -> (allocations.getOrElse(c.id, c.defaultPercent) - bench.p50)))
      // The first category whose deviation is strictly the largest, as upstream's loop finds it.
      val notable = deviations.foldLeft(Option.empty[(BudgetCategory, Double)]) {
        case (best, (c, d)) => if math.abs(d) > best.fold(0.0)(b => math.abs(b._2)) then Some(c -> d) else best
      }
      val summary = Dom.byId[dom.HTMLElement]("comparison-summary")
      notable match
        case Some((c, d)) if math.abs(d) > 3 =>
          summary.innerHTML = s"""
      vs. Industry: <span class="comparison-highlight">${c.name.replace("&", "&amp;")}</span>
       ${Js.str(math.abs(Js.round(d)))}% ${if d > 0 then "above" else "below"} avg
    """
        case _ => summary.textContent = "vs. Industry: similar to peers"

  // ------------------------------------------------------------ selectors, init

  private def initSelectors(cfg: BudgetConfig, a: BudgetAnalytics, ui: Ui): Unit =
    val budget = Dom.byId[dom.HTMLSelectElement]("budget-selector")
    budget.innerHTML = ""
    cfg.presetBudgets.foreach { amount =>
      val option = dom.document.createElement("option").asInstanceOf[dom.HTMLOptionElement]
      option.value = Js.str(amount)
      option.textContent = formatCurrencyFull(amount)
      option.selected = amount == cfg.defaultBudget
      budget.appendChild(option)
    }
    ui.on(budget, "change")(_ => IO { totalBudget = budget.value.toDouble; updateAllSliderAmounts(); updateStatusBar() })
    val stages = Dom.byId[dom.HTMLSelectElement]("stage-selector")
    stages.innerHTML = ""
    a.stages.foreach { s =>
      val option = dom.document.createElement("option").asInstanceOf[dom.HTMLOptionElement]
      option.value = s
      option.textContent = s
      option.selected = s == a.defaultStage
      stages.appendChild(option)
    }
    ui.on(stages, "change")(_ => IO { selectedStage = stages.value; updateAllPercentileBadges(); updateComparisonSummary() })

  private def initializeUI(data: BudgetData, ui: Ui): IO[Unit] =
    IO {
      config = Some(data.config)
      analytics = Some(data.analytics)
      totalBudget = data.config.defaultBudget
      selectedStage = data.analytics.defaultStage
      allocations = scala.collection.mutable.LinkedHashMap.from(data.config.categories.map(c => c.id -> c.defaultPercent))
      initSelectors(data.config, data.analytics, ui)
      val sliders = Dom.byId[dom.HTMLElement]("sliders-container")
      sliders.innerHTML = ""
      data.config.categories.foreach { c =>
        sliders.appendChild(sliderRow(c, data.analytics.history.map(_.allocations.getOrElse(c.id, 0.0)), ui))
      }
      chart.foreach(_.destroy())
      chart = Some(initChart(data.config.categories))
      updateAllPercentileBadges()
      updateStatusBar()
      updateComparisonSummary()
    } >> log.info("UI initialized with", data.analytics.history.size, "months of history")

  // ------------------------------------------------------------ the View's tools

  /** `JSON.stringify(value, null, 2)`. */
  private val pretty = Printer.spaces2.copy(colonLeft = "")

  final case class Pretty[A](value: A)
  private given [A: Encoder.AsObject: JsonSchemaOf]: ToolOutput[Pretty[A]] with
    val schema                                 = summon[JsonSchemaOf[A]].schema.asObject
    def result(p: Pretty[A]): CallToolResult = CallToolResult.structured(pretty.print(p.value.asJson), p.value.asJson)

  private val notLoaded = Left("Error: Configuration not loaded")

  override def tools: IO[Option[ViewToolProvider[IO]]] =
    ViewTools
      .of[IO](
        ViewTool[NoArgs](
          "get-allocations",
          "Get the current budget allocations including total budget, percentages, and amounts per category",
          title = Some("Get Budget Allocations")
        )(_ =>
          IO(config.fold[Either[String, Pretty[Allocations]]](notLoaded) { cfg =>
            Right(
              Pretty(
                Allocations(
                  totalBudget,
                  cfg.currency,
                  cfg.currencySymbol,
                  selectedStage,
                  scala.collection.immutable.ListMap.from(cfg.categories.map { c =>
                    val p = allocations.getOrElse(c.id, 0.0)
                    c.id -> AllocationAmount(p, p / 100 * totalBudget)
                  }),
                  cfg.categories.map(c => CategoryRef(c.id, c.name, c.color))
                )
              )
            )
          })
        ),
        ViewTool[SetAllocation]("set-allocation", "Set the allocation percentage for a specific budget category", title = Some("Set Category Allocation"))(args =>
          IO(config.fold[Either[String, String]](notLoaded) { cfg =>
            cfg.categories.find(_.id == (args.categoryId: String)) match
              case None =>
                Left(s"""Error: Category "${args.categoryId: String}" not found. Available: ${cfg.categories.map(_.id).mkString(", ")}""")
              case Some(category) =>
                handleSliderChange(category.id, args.percent)
                Option(dom.document.querySelector(s""".slider-row[data-category-id="${category.id}"] .slider"""))
                  .foreach(_.asInstanceOf[dom.HTMLInputElement].value = Js.str(args.percent))
                Right(s"Set ${category.name} allocation to ${Js.fixed(args.percent, 1)}% (${cfg.currencySymbol}${Js.locale(args.percent / 100 * totalBudget)})")
          })
        ),
        ViewTool[SetTotalBudget]("set-total-budget", "Set the total budget amount", title = Some("Set Total Budget"))(args =>
          IO(config.fold[Either[String, String]](notLoaded) { cfg =>
            totalBudget = args.amount
            val budget = Dom.byId[dom.HTMLSelectElement]("budget-selector")
            if (0 until budget.options.length).exists(i => budget.options(i).value.toDoubleOption.contains(args.amount: Double)) then budget.value = Js.str(args.amount)
            updateAllSliderAmounts()
            updateStatusBar()
            updateComparisonSummary()
            Right(s"Total budget set to ${cfg.currencySymbol}${Js.locale(args.amount)}")
          })
        ),
        ViewTool[SetStage](
          "set-company-stage",
          "Set the company stage for benchmark comparison (e.g., 'Seed', 'Series A', 'Series B', 'Growth')",
          title = Some("Set Company Stage")
        )(args =>
          IO(analytics.fold[Either[String, String]](Left("Error: Analytics not loaded")) { a =>
            val stage: String = args.stage
            if !a.stages.contains(stage) then Left(s"""Error: Stage "$stage" not found. Available: ${a.stages.mkString(", ")}""")
            else
              selectedStage = stage
              Dom.byId[dom.HTMLSelectElement]("stage-selector").value = stage
              updateAllPercentileBadges()
              updateComparisonSummary()
              Right(s"""Company stage set to "$stage"""")
          })
        ),
        ViewTool[NoArgs](
          "get-benchmark-comparison",
          "Compare current allocations against industry benchmarks for the selected stage",
          title = Some("Get Benchmark Comparison")
        )(_ =>
          IO((config, analytics).tupled.fold[Either[String, Pretty[BenchmarkComparison]]](Left("Error: Data not loaded")) { (cfg, _) =>
            stageBenchmark.toRight(s"""Error: No benchmark data for stage "$selectedStage"""").map { b =>
              Pretty(
                BenchmarkComparison(
                  selectedStage,
                  scala.collection.immutable.ListMap.from(cfg.categories.flatMap { c =>
                    b.categoryBenchmarks.get(c.id).map { bench =>
                      val current = allocations.getOrElse(c.id, 0.0)
                      val status  = if current < bench.p25 then "below p25" else if current > bench.p75 then "above p75" else "within range"
                      c.id -> Comparison(current, bench.p25, bench.p50, bench.p75, status)
                    }
                  })
                )
              )
            }
          })
        )
      )
      .map(tools => Some(tools))

  // ------------------------------------------------------------ the View

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    IO {
      Chart.register(ChartJs.registerables.toSeq*)
      dom.window
        .matchMedia("(prefers-color-scheme: dark)")
        .addEventListener("change", (_: dom.Event) => ui.run(IO(for c <- chart; cfg <- config do { c.destroy(); chart = Some(initChart(cfg.categories)) })))
    } >>
      SafeArea.follow(view, Dom.query[dom.HTMLElement](".app-container")).surround(
        view.events
          .evalMap {
            case HostEvent.ToolResult(result) =>
              log.info("Received tool result:", result) >>
                result.structuredAs[BudgetData].fold(_ => IO.unit, initializeUI(_, ui))
            case HostEvent.Unreadable(method, _, why) => log.error(s"$method: $why")
            case _                                    => IO.unit
          }
          .compile
          .drain
      )
