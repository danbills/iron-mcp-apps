package ironmcp.extapps.segmentation

import cats.effect.IO
import cats.effect.std.Random
import munit.CatsEffectSuite

class SegmentationSuite extends CatsEffectSuite:

  test("250 customers, unique names, ids cust-0001.., values within each segment's widened range"):
    Random.scalaUtilRandomSeedInt[IO](3).flatMap(Generator.customers(_)).map { cs =>
      assertEquals(cs.size, 250)
      assertEquals(cs.map(_.name).distinct.size, 250)
      assertEquals(cs.head.id, "cust-0001")
      assertEquals(cs.last.id, "cust-0250")
      assert(cs.forall(c => Segments.all.contains(c.segment)))
      val enterprise = cs.filter(_.segment == "Enterprise")
      assert(enterprise.forall(c => c.annualRevenue >= 1600000 && c.annualRevenue <= 12000000))
      val summaries = Generator.summaries(cs)
      assertEquals(summaries.map(_.count).sum, 250)
      assertEquals(summaries.map(_.name), Segments.all)
    }
