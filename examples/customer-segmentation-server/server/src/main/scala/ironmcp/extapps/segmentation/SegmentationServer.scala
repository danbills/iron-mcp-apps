package ironmcp.extapps.segmentation

import cats.effect.std.Random
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Decoder
import io.circe.derivation.{Configuration, ConfiguredEnumCodec}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.{ConfiguredEnumJsonSchemaOf, JsonSchemaOf}
import ironmcp.server.*

given Configuration = Configuration.default.withTransformConstructorNames {
  case "MidMarket" => "Mid-Market"
  case other       => other
}

enum SegmentFilter derives ConfiguredEnumCodec, ConfiguredEnumJsonSchemaOf:
  case All, Enterprise, MidMarket, SMB, Startup

final case class CustomerArgs(segment: Option[SegmentFilter :| DescribedAs[True, "Filter by segment (default: All)"]] = None)
    derives Decoder, JsonSchemaOf

/** Upstream's data generator: 250 companies in four clusters, Gaussian around each segment's ranges. */
object Generator:
  private val Prefixes = Vector("Apex", "Nova", "Prime", "Vertex", "Atlas", "Quantum", "Summit", "Nexus", "Titan", "Pinnacle", "Zenith", "Vanguard", "Horizon", "Stellar", "Onyx", "Cobalt", "Vector", "Pulse", "Forge", "Spark")
  private val Cores    = Vector("Tech", "Data", "Cloud", "Logic", "Sync", "Flow", "Core", "Net", "Soft", "Wave", "Link", "Mind", "Byte", "Grid", "Hub")
  private val Suffixes = Vector("Corp", "Inc", "Solutions", "Systems", "Labs", "Group", "Industries", "Dynamics", "Partners", "Ventures", "Global", "Digital")

  private type Range = (Double, Double)
  private final case class Cluster(revenue: Range, employees: Range, age: Range, engagement: Range, tickets: Range, nps: Range)
  private val Clusters = Map(
    "Enterprise" -> Cluster((2e6, 10e6), (500, 5000), (60, 120), (70, 95), (5, 20), (40, 80)),
    "Mid-Market" -> Cluster((5e5, 2e6), (100, 500), (36, 84), (60, 85), (10, 30), (20, 60)),
    "SMB"        -> Cluster((5e4, 5e5), (10, 100), (12, 48), (40, 70), (15, 40), (0, 40)),
    "Startup"    -> Cluster((1e4, 2e5), (1, 50), (1, 24), (50, 90), (5, 25), (10, 70))
  )
  private val Weights = List("Enterprise" -> 0.15, "Mid-Market" -> 0.25, "SMB" -> 0.35, "Startup" -> 0.25)

  def customers(random: Random[IO], count: Int = 250): IO[List[Customer]] =
    def pick[A](items: Vector[A]) = random.nextDouble.map(r => items(math.floor(r * items.size).toInt))
    def gaussian(mean: Double, sd: Double) =
      (random.nextDouble, random.nextDouble).mapN((u1, u2) => math.sqrt(-2.0 * math.log(u1)) * math.cos(2.0 * math.Pi * u2) * sd + mean)
    def clustered(range: Range): IO[Long] =
      val (min, max) = range
      gaussian((min + max) / 2, (max - min) / 4).map(v => math.round(math.max(min * 0.8, math.min(max * 1.2, v))))
    def segment: IO[String] =
      random.nextDouble.map(r => Weights.scanLeft(("", 0.0))((acc, w) => (w._1, acc._2 + w._2)).tail.find(r < _._2).fold("SMB")(_._1))
    def name(used: Set[String]): IO[String] =
      def attempt(n: Int): IO[String] =
        if n >= 100 then (pick(Prefixes), pick(Cores), random.nextDouble).mapN((p, c, r) => s"$p $c ${math.floor(r * 1000).toInt}")
        else (pick(Prefixes), pick(Cores), pick(Suffixes)).mapN((p, c, s) => s"$p $c $s").flatMap(n0 => if used(n0) then attempt(n + 1) else IO.pure(n0))
      attempt(0)
    (1 to count).toList
      .foldLeftM((List.empty[Customer], Set.empty[String])) { case ((acc, used), i) =>
        for
          seg  <- segment
          c     = Clusters(seg)
          n    <- name(used)
          vals <- List(c.revenue, c.employees, c.age, c.engagement, c.tickets, c.nps).traverse(clustered)
        yield (Customer(f"cust-$i%04d", n, seg, vals(0), vals(1), vals(2), vals(3), vals(4), vals(5)) :: acc, used + n)
      }
      .map(_._1.reverse)

  def summaries(customers: List[Customer]): List[SegmentSummary] =
    Segments.all.map(s => SegmentSummary(s, customers.count(_.segment == s), Segments.colors(s)))

/** Upstream's customer-segmentation-server. The customers are made once and
  * kept, as upstream's module-level cache keeps them.
  */
object SegmentationServer:

  val ResourceUri: UiUri = "ui://customer-segmentation/mcp-app.html"

  def apply(html: String, cache: Ref[IO, Option[CustomerData]], random: Random[IO]): McpServer[IO] =
    val all: IO[CustomerData] = cache.get.flatMap {
      case Some(data) => IO.pure(data)
      case None =>
        Generator.customers(random).flatMap(cs => cache.modify {
          case Some(data) => (Some(data), data)
          case None       => val data = CustomerData(cs, Generator.summaries(cs)); (Some(data), data)
        })
    }
    val tool = McpTool[CustomerArgs](
      "get-customer-data",
      "Returns customer data with segment information for visualization. Optionally filter by segment.",
      title = Some("Get Customer Data")
    )(args =>
      all.map { data =>
        args.segment.map(s => (s: SegmentFilter)) match
          case None | Some(SegmentFilter.All) => data
          case Some(s) =>
            val name = summon[Configuration].transformConstructorNames(s.toString)
            data.copy(customers = data.customers.filter(_.segment == name))
      }
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "Customer Segmentation Server", version = "1.0.0"),
      tools = Some(ToolSet.of(tool)),
      resources = Some(UiResources.of[IO](UiResource(ResourceUri, "ui://customer-segmentation/mcp-app.html", html, Some("Customer Segmentation Explorer UI")))),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] =
    (ViewPage.load("customer-segmentation-server/view"), Ref.of[IO, Option[CustomerData]](None), Random.scalaUtilRandom[IO]).mapN(apply)

object Main extends ExampleMain("customer-segmentation-server", _ => SegmentationServer.load)
