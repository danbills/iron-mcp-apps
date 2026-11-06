package ironmcp.extapps.cohort

import cats.effect.IO
import cats.effect.std.Random
import munit.CatsEffectSuite

class CohortSuite extends CatsEffectSuite:

  test("a triangle of cells: cohort c has min(count - c, periods) of them, period 0 at 100%, none above the last plus 2 points"):
    Random.scalaUtilRandomSeedInt[IO](7).flatMap(CohortServer.generate(_, Metric.retention, PeriodType.monthly, 12, 12)).map { data =>
      assertEquals(data.cohorts.map(_.cells.size), (12 to 1 by -1).toList)
      assert(data.cohorts.forall(_.cells.head.retention == 1.0))
      for row <- data.cohorts; pair <- row.cells.sliding(2) if pair.size == 2 do assert(pair(1).retention <= pair(0).retention + 0.02 + 1e-12)
      assertEquals(data.periods.take(2), List("M0", "M1"))
      assert(data.cohorts.head.cohortLabel.matches("[A-Z][a-z]{2} \\d{4}"), data.cohorts.head.cohortLabel)
    }

  test("the summary as upstream writes it"):
    Random.scalaUtilRandomSeedInt[IO](7).flatMap(CohortServer.generate(_, Metric.revenue, PeriodType.weekly, 3, 3)).map { data =>
      val text = CohortServer.given_Show_CohortData.show(data)
      assert(text.startsWith("Cohort Analysis: 3 cohorts, 3 periods\nAverage retention: "), text)
      assert(text.endsWith("%\nMetric: revenue, Period: weekly"), text)
    }
