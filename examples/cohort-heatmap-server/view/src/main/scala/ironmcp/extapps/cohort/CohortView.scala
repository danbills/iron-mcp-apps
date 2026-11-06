package ironmcp.extapps.cohort

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import io.github.iltotore.iron.autoRefine
import ironmcp.apps.view.*
import ironmcp.extapps.view.{Dom, ExampleView, Js, Ui}
import ironmcp.protocol.Implementation
import org.scalajs.dom

/** Upstream's cohort heatmap (React there): the View fetches its own data
  * (`get-cohort-data`) for the chosen metric, and shows a retention grid with
  * row/column highlight on click and a tooltip on hover.
  */
object CohortView extends ExampleView:

  val appInfo: Implementation   = Implementation(name = "Cohort Heatmap", version = "1.0.0")
  override def theming: Boolean = false

  private final case class Tip(x: Double, y: Double, cohort: String, period: String, retention: Double, retained: Int, original: Int)

  // State (one JS thread; changed inside IO only).
  private var data: Option[CohortData]       = None
  private var loading                        = true
  private var metric                         = "retention"
  private var periodType                     = "monthly"
  private var highlightedCohort: Option[Int] = None
  private var highlightedPeriod: Option[Int] = None

  private def color(retention: Double): String =
    s"hsl(${Js.str(retention * 120)}, 70%, ${Js.str(45 + (1 - retention) * 15)}%)"

  private def hl(on: Boolean): String = if on then "highlighted" else ""

  private def escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

  private val legend =
    List(0.9 -> "80-100%", 0.65 -> "50-79%", 0.35 -> "20-49%", 0.1 -> "0-19%")
      .map((r, label) => s"""<span class="legendItem"><span class="legendColor" style="background-color: ${color(r)};"></span>$label</span>""")
      .mkString("""<div class="legend">""", "", "</div>")

  private def header: String =
    def option(value: String, label: String, current: String) =
      s"""<option value="$value"${if value == current then " selected" else ""}>$label</option>"""
    s"""<header class="header"><h1 class="title">Cohort Retention Analysis</h1><div class="controls">""" +
      s"""<label class="control"><span>Metric:</span><select id="metric">${option("retention", "Retention %", metric)}${option("revenue", "Revenue Retention", metric)}${option("active", "Active Users", metric)}</select></label>""" +
      s"""<label class="control"><span>Period:</span><select id="period">${option("monthly", "Monthly", periodType)}${option("weekly", "Weekly", periodType)}</select></label>""" +
      "</div></header>"

  private def grid(d: CohortData): String =
    val periods = d.periods.zipWithIndex.map((period, i) => s"""<div class="headerPeriod ${hl(highlightedPeriod.contains(i))}">${escape(period)}</div>""")
    val rows = d.cohorts.zipWithIndex.map { (cohort, c) =>
      val rowLit = highlightedCohort.contains(c)
      val label =
        s"""<div class="label ${hl(rowLit)}"><span class="cohortName">${escape(cohort.cohortLabel)}</span><span class="cohortSize">${Js.locale(cohort.originalUsers)}</span></div>"""
      val cells = (0 until d.periods.size).map { p =>
        cohort.cells.find(_.periodIndex == p) match
          case None => """<div class="cellEmpty"></div>"""
          case Some(cell) =>
            s"""<div class="cell ${hl(rowLit || highlightedPeriod.contains(p))}" data-c="$c" data-p="$p" style="background-color: ${color(cell.retention)};">${Js.str(Js.round(cell.retention * 100))}</div>"""
      }
      label + cells.mkString
    }
    s"""<div class="heatmapWrapper"><div class="heatmapGrid" style="grid-template-columns: 120px repeat(${d.periods.size}, 44px);"><div class="headerCorner"></div>${periods.mkString}${rows.mkString}</div></div>"""

  private def tooltip(t: Tip): String =
    val left = if t.x + 200 > dom.window.innerWidth then t.x - 216 else t.x
    s"""<div class="tooltip" style="left: ${Js.str(left)}px; top: ${Js.str(t.y)}px;">""" +
      s"""<div class="tooltipHeader">${escape(t.cohort)} — ${escape(t.period)}</div>""" +
      s"""<div class="tooltipRow"><span class="tooltipLabel">Retention:</span><span class="tooltipValue">${Js.fixed(t.retention * 100, 1)}%</span></div>""" +
      s"""<div class="tooltipRow"><span class="tooltipLabel">Users:</span><span class="tooltipValue">${Js.locale(t.retained)} / ${Js.locale(t.original)}</span></div>""" +
      "</div>"

  private def main: dom.HTMLElement = Dom.query[dom.HTMLElement]("main.container")

  /** Renders everything but the tooltip, which hovering changes on its own. */
  private def render(view: McpAppView[IO], ui: Ui): Unit =
    val content = if loading then """<div class="loading">Loading...</div>""" else data.fold("""<div class="error">Failed to load data</div>""")(grid)
    main.innerHTML = header + content + legend
    val metricSelect = Dom.byId[dom.HTMLSelectElement]("metric")
    val periodSelect = Dom.byId[dom.HTMLSelectElement]("period")
    ui.on(metricSelect, "change")(_ => IO { metric = metricSelect.value; highlightedCohort = None; highlightedPeriod = None } >> fetch(view, ui))
    ui.on(periodSelect, "change")(_ => IO { periodType = periodSelect.value; highlightedCohort = None; highlightedPeriod = None } >> fetch(view, ui))
    for d <- data; cell <- Dom.queryAll[dom.HTMLElement](".cell[data-c]") do
      val c = cell.getAttribute("data-c").toInt
      val p = cell.getAttribute("data-p").toInt
      ui.onClick(cell)(IO { highlightedCohort = Some(c); highlightedPeriod = Some(p); render(view, ui) })
      cell.addEventListener(
        "mouseenter",
        (_: dom.Event) =>
          d.cohorts(c).cells.find(_.periodIndex == p).foreach { data =>
            val rect = cell.getBoundingClientRect()
            showTip(Some(Tip(rect.right + 8, rect.top, d.cohorts(c).cohortLabel, d.periodLabels(p), data.retention, data.usersRetained, data.usersOriginal)))
          }
      )
      cell.addEventListener("mouseleave", (_: dom.Event) => showTip(None))

  private def showTip(tip: Option[Tip]): Unit =
    Dom.queryAll[dom.HTMLElement]("main.container > .tooltip").foreach(_.remove())
    tip.foreach(t => main.insertAdjacentHTML("beforeend", tooltip(t)))

  private def fetch(view: McpAppView[IO], ui: Ui): IO[Unit] =
    val args = JsonObject("metric" -> metric.asJson, "periodType" -> periodType.asJson, "cohortCount" -> 12.asJson, "maxPeriods" -> 12.asJson)
    IO { loading = true; render(view, ui) } >>
      view
        .callServerTool("get-cohort-data", args)
        .flatMap(result => IO { data = result.structuredAs[CohortData].toOption })
        .handleErrorWith(e => IO(dom.console.error("Failed to fetch cohort data:", e.toString)))
        .guarantee(IO { loading = false; render(view, ui) })

  def start(view: McpAppView[IO], ui: Ui): IO[Unit] =
    IO(Dom.byId[dom.HTMLElement]("root").innerHTML = """<main class="container"></main>""") >>
      fetch(view, ui) >>
      SafeArea.follow(view, main).surround(view.events.compile.drain)
