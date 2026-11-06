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

lazy val exampleServers = Seq(basicServer, debugServer, integrationServer, qrServer, quickstartServer, videoServer)
lazy val exampleViews   = Seq(basicView, debugView, integrationView, qrView, quickstartView, videoView)
lazy val examples: Seq[ProjectReference] = (exampleServers ++ exampleViews).map(p => p: ProjectReference)

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
