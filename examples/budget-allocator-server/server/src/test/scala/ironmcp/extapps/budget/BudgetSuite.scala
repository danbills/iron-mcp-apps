package ironmcp.extapps.budget

import io.circe.parser.parse
import io.circe.syntax.*

import java.time.LocalDate
import scala.io.Source

class BudgetSuite extends munit.FunSuite:

  /** Upstream's generator run under Node for these dates (see the resource's maker in git history). */
  private val upstream = parse(Source.fromResource("history-upstream.json").mkString).toOption.get

  for date <- List("2026-11-06", "2026-03-31") do
    test(s"history for $date is upstream's, number for number (the LCG, the month arithmetic)"):
      val ours = BudgetServer.history(LocalDate.parse(date)).asJson
      assertEquals(ours, upstream.hcursor.downField(date).focus.get)

  test("the summary text, as upstream formats it"):
    val text = BudgetServer.given_Show_BudgetData.show(BudgetServer.data(LocalDate.parse("2026-11-06")))
    assert(text.contains("Default Budget: $100,000"), text)
    assert(text.contains("Available Presets: $50,000, $100,000, $250,000, $500,000"), text)
    assert(text.contains("  - R&D: 10% default"), text)
    assert(text.endsWith("Default Stage: Series A"), text)
