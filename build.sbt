val scala3Version = "3.9.0"
// Local snapshot from ~/iron-mcp (`sbt 'set ThisBuild / version := "0.5.0-SNAPSHOT"; publishLocal'`).
val ironMcpVersion = "0.5.0-SNAPSHOT"
val http4sVersion  = "0.23.37"

ThisBuild / scalaVersion := scala3Version

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Werror"
)

val testDeps = Seq(
  "org.scalameta" %% "munit"             % "1.3.5" % Test,
  "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test
)

// What every example server shares: the launcher (stdio or Streamable HTTP, as
// upstream's main.ts) and loading a bundled View.
lazy val shared = (project in file("examples/shared"))
  .settings(
    name := "ext-apps-shared",
    libraryDependencies ++= Seq(
      "io.github.danbills" %% "iron-mcp-core" % ironMcpVersion,
      "io.github.danbills" %% "iron-mcp-http" % ironMcpVersion,
      "io.github.danbills" %% "iron-mcp-apps" % ironMcpVersion
    ) ++ testDeps
  )

// What every example View shares (Scala.js): connecting as ext-apps' App does
// by default, and the DOM-to-IO bridge.
lazy val viewKit = (project in file("examples/view-kit"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "ext-apps-view-kit",
    libraryDependencies ++= Seq("io.github.danbills" %% "iron-mcp-apps-view" % ironMcpVersion)
  )

// Scala.js facades for the JS libraries the Views use (adr/0001): typed, and
// only as much of each library as the Views need.
lazy val chartjs = (project in file("facades/chartjs"))
  .enablePlugins(ScalaJSPlugin)
  .settings(name := "facade-chartjs", libraryDependencies += "org.scala-js" %% "scalajs-dom" % "2.8.1")

/** Types an example's server and View share (JVM and JS): one definition for
  * the output schema, the encoder and the View's decoder.
  */
def model(id: String, dir: String) =
  sbtcrossproject.CrossProject(s"${id}Model", file(s"examples/$dir/model"))(JVMPlatform, JSPlatform)
    .crossType(CrossType.Pure)
    .withoutSuffixFor(JVMPlatform)
    .settings(name := s"$dir-model", libraryDependencies += "io.github.danbills" %% "iron-mcp-protocol" % ironMcpVersion)

/** An example's View: Scala.js on iron-mcp-apps-view, bundled by esbuild (ViewBundlePlugin). */
def view(id: String, dir: String): Project =
  Project(s"${id}View", file(s"examples/$dir/view"))
    .enablePlugins(ViewBundlePlugin)
    .dependsOn(viewKit)
    .settings(
      name := s"$dir-view",
      libraryDependencies ++= Seq("io.github.danbills" %% "iron-mcp-apps-view" % ironMcpVersion) ++ testDeps
    )

/** Views' bundles, copied among their server's resources at `views/<example>/<view>/`. */
def viewResources(views: Seq[Project]): Def.Initialize[Task[Seq[File]]] =
  val bundles = views.map(v => v / viewBundle).join
  val paths   = views.map(v => s"${v.base.getParentFile.getName}/${v.base.getName}")
  Def.task {
    Def.uncached {
      val root = (Compile / resourceManaged).value / "views"
      bundles.value.zip(paths).flatMap { (bundle, path) =>
        val out = root / path
        IO.delete(out)
        IO.copyDirectory(bundle, out)
        (out ** "*").get().filter(_.isFile)
      }
    }
  }

/** An example's server, with its Views' bundles among its resources. */
def server(id: String, dir: String, views: Project*): Project =
  Project(s"${id}Server", file(s"examples/$dir/server"))
    .dependsOn(shared)
    .settings(
      name := s"$dir-server",
      libraryDependencies ++= testDeps,
      run / fork         := true,
      run / connectInput := true,
      Compile / resourceGenerators += viewResources(views).taskValue
    )

lazy val basicView        = view("basic", "basic-server")
lazy val basicServer      = server("basic", "basic-server", basicView)
lazy val budgetModel       = model("budget", "budget-allocator-server")
lazy val budgetView        = view("budget", "budget-allocator-server").dependsOn(budgetModel.js, chartjs)
lazy val budgetServer      = server("budget", "budget-allocator-server", budgetView).dependsOn(budgetModel.jvm)
lazy val cohortModel       = model("cohort", "cohort-heatmap-server")
lazy val cohortView        = view("cohort", "cohort-heatmap-server").dependsOn(cohortModel.js)
lazy val cohortServer      = server("cohort", "cohort-heatmap-server", cohortView).dependsOn(cohortModel.jvm)
lazy val debugView         = view("debug", "debug-server")
lazy val debugServer       = server("debug", "debug-server", debugView)
lazy val integrationView   = view("integration", "integration-server")
lazy val integrationServer = server("integration", "integration-server", integrationView)
lazy val quickstartView   = view("quickstart", "quickstart")
lazy val quickstartServer = server("quickstart", "quickstart", quickstartView)

lazy val qrView            = view("qr", "qr-server")
lazy val qrServer          = server("qr", "qr-server", qrView).settings(
  libraryDependencies ++= Seq("com.google.zxing" % "core" % "3.5.3", "com.google.zxing" % "javase" % "3.5.3" % Test)
)
lazy val videoView         = view("video", "video-resource-server")
lazy val videoServer       = server("video", "video-resource-server", videoView)

lazy val exampleServers = Seq(basicServer, budgetServer, cohortServer, debugServer, integrationServer, qrServer, quickstartServer, videoServer)
lazy val exampleViews   = Seq(basicView, budgetView, cohortView, debugView, integrationView, qrView, quickstartView, videoView)
lazy val facades: Seq[ProjectReference] = Seq(chartjs)
lazy val models: Seq[ProjectReference]  = Seq(budgetModel.jvm, budgetModel.js, cohortModel.jvm, cohortModel.js)
lazy val examples: Seq[ProjectReference] = (exampleServers ++ exampleViews).map(p => p: ProjectReference) ++ facades ++ models

// Every server in one JVM, as upstream's run-all.ts does, for basic-host and the e2e suite.
lazy val runAll = (project in file("examples/run-all"))
  .dependsOn(exampleServers.map(p => p: ClasspathDep[ProjectReference])*)
  .settings(
    name               := "ext-apps-run-all",
    run / fork         := true,
    run / connectInput := false
  )

lazy val root = (project in file("."))
  .aggregate((Seq[ProjectReference](shared, viewKit, runAll) ++ examples)*)
  .settings(name := "iron-mcp-ext-apps")
