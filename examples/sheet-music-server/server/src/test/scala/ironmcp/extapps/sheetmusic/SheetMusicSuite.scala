package ironmcp.extapps.sheetmusic

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class SheetMusicSuite extends CatsEffectSuite:

  override val munitIOTimeout: Duration = 2.minutes

  private val parser = ResourceSuiteLocalFixture("parser", AbcParser.resource)
  override def munitFixtures = List(parser)

  test("upstream's default tune parses with no warnings"):
    parser().warnings(SheetMusicServer.DefaultTune).assertEquals(Nil)

  test("broken notation gets abcjs's own warnings, without their HTML"):
    parser().warnings("X:1\nK:C\nC D E F | G A B c |]\n%%%\nZZZ!!!@@@").map { warnings =>
      assert(warnings.nonEmpty)
      assert(warnings.forall(w => !w.contains("<")), warnings)
    }

  test("one parser serves calls in turn"):
    List.fill(4)(parser().warnings(SheetMusicServer.DefaultTune)).parSequence.map(r => assert(r.forall(_.isEmpty)))

  test("the input schema offers upstream's tune as the default, so a host can fill it in"):
    val schema = summon[ironmcp.schema.JsonSchemaOf[PlayArgs]].schema
    println(schema.spaces2)
    assertEquals(schema.hcursor.downField("properties").downField("abcNotation").get[String]("default"), Right(SheetMusicServer.DefaultTune))
