package ironmcp.extapps.scenario

class ScenarioSuite extends munit.FunSuite:

  test("the templates' summaries are what upstream's insights say"):
    val byId = ScenarioServer.Templates.map(t => t.id -> t.summary).toMap
    assertEquals(byId("bootstrapped").breakEvenMonth, Some(1))
    assert(byId("vc-rocketship").breakEvenMonth.forall(_ > 1))
    assert(byId("vc-rocketship").endingMRR > 3 * 100000 * 0.99)
    assert(byId("turnaround").mrrGrowthPct < 0)

  test("projections compound monthly at growth minus churn"):
    val ps = Calculations.projections(ScenarioInputs(100, 10, 0, 50, 10))
    assertEqualsDouble(ps.head.mrr, 110, 1e-9)
    assertEqualsDouble(ps(1).mrr, 121, 1e-9)
    assertEqualsDouble(ps(1).cumulativeRevenue, 231, 1e-9)
    assertEqualsDouble(ps.head.netProfit, 45, 1e-9)

  test("the text lists the templates, and a custom scenario when asked"):
    val (ps, s) = Calculations.scenario(ScenarioServer.DefaultInputs)
    val text    = ScenarioServer.given_Show_ScenarioData.show(ScenarioData(ScenarioServer.Templates, ScenarioServer.DefaultInputs, Some(ps), Some(s)))
    assert(text.contains("  🚀 VC Rocketship: High burn, explosive growth, raise more later"), text)
    assert(text.contains("Custom Scenario:\n  Ending MRR: $"), text)
