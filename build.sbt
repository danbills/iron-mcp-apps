val scala3Version = "3.9.0"
// Published from ~/iron-mcp (v0.5.0).
val ironMcpVersion = "0.5.0"
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

lazy val abcjs = (project in file("facades/abcjs"))
  .enablePlugins(ScalaJSPlugin)
  .settings(name := "facade-abcjs", libraryDependencies += "org.scala-js" %% "scalajs-dom" % "2.8.1")

lazy val webspeech = (project in file("facades/webspeech"))
  .enablePlugins(ScalaJSPlugin)
  .settings(name := "facade-webspeech", libraryDependencies += "org.scala-js" %% "scalajs-dom" % "2.8.1")

lazy val shadertoyFacade = (project in file("facades/shadertoy"))
  .enablePlugins(ScalaJSPlugin)
  .settings(name := "facade-shadertoy", libraryDependencies += "org.scala-js" %% "scalajs-dom" % "2.8.1")

lazy val threeFacade = (project in file("facades/three"))
  .enablePlugins(ScalaJSPlugin)
  .settings(name := "facade-three", libraryDependencies += "org.scala-js" %% "scalajs-dom" % "2.8.1")

lazy val forcegraphFacade = (project in file("facades/forcegraph"))
  .enablePlugins(ScalaJSPlugin)
  .settings(name := "facade-forcegraph", libraryDependencies += "org.scala-js" %% "scalajs-dom" % "2.8.1")

lazy val pdfjsFacade = (project in file("facades/pdfjs"))
  .enablePlugins(ScalaJSPlugin)
  .settings(name := "facade-pdfjs", libraryDependencies += "org.scala-js" %% "scalajs-dom" % "2.8.1")

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
  viewAt(id, dir, file(s"examples/$dir/view"))

def viewAt(id: String, projName: String, base: File): Project =
  Project(s"${id}View", base)
    .enablePlugins(ViewBundlePlugin)
    .dependsOn(viewKit)
    .settings(
      name := s"$projName-view",
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
lazy val segmentationModel  = model("segmentation", "customer-segmentation-server")
lazy val segmentationView   = view("segmentation", "customer-segmentation-server").dependsOn(segmentationModel.js, chartjs)
lazy val segmentationServer = server("segmentation", "customer-segmentation-server", segmentationView).dependsOn(segmentationModel.jvm)
lazy val scenarioModel     = model("scenario", "scenario-modeler-server")
lazy val scenarioView      = view("scenario", "scenario-modeler-server").dependsOn(scenarioModel.js, chartjs)
lazy val scenarioServer    = server("scenario", "scenario-modeler-server", scenarioView).dependsOn(scenarioModel.jvm)
lazy val monitorModel      = model("monitor", "system-monitor-server")
lazy val monitorView       = view("monitor", "system-monitor-server").dependsOn(monitorModel.js, chartjs)
lazy val monitorServer     = server("monitor", "system-monitor-server", monitorView).dependsOn(monitorModel.jvm).settings(
  libraryDependencies += "com.github.oshi" % "oshi-core" % "6.6.6"
)
val graalVersion = "25.0.5"
lazy val sheetMusicView    = view("sheetMusic", "sheet-music-server").dependsOn(abcjs)
lazy val sheetMusicServer  = server("sheetMusic", "sheet-music-server", sheetMusicView).settings(
  // abcjs's parser, run in GraalJS.
  libraryDependencies ++= Seq(
    "org.graalvm.polyglot" % "polyglot"        % graalVersion,
    "org.graalvm.js"       % "js-language"     % graalVersion,
    "org.graalvm.truffle"  % "truffle-runtime" % graalVersion
  ),
  Compile / resourceGenerators += Def.task {
    Def.uncached {
      val script = (LocalRootProject / baseDirectory).value / "node_modules" / "abcjs" / "dist" / "abcjs-basic-min.js"
      val out    = (Compile / resourceManaged).value / "abcjs" / "abcjs-basic-min.js"
      IO.copyFile(script, out)
      Seq(out)
    }
  }.taskValue
)
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
lazy val transcriptView    = view("transcript", "transcript-server").dependsOn(webspeech)
lazy val transcriptServer  = server("transcript", "transcript-server", transcriptView)
lazy val lazyAuthButtonView = viewAt("lazyAuthButton", "lazy-auth-button", file("examples/lazy-auth-server/button-view"))
lazy val lazyAuthSecretView = viewAt("lazyAuthSecret", "lazy-auth-secret", file("examples/lazy-auth-server/secret-view"))
lazy val lazyAuthServer     = server("lazyAuth", "lazy-auth-server", lazyAuthButtonView, lazyAuthSecretView).settings(
  libraryDependencies ++= Seq(
    "io.github.danbills" %% "iron-mcp-auth"        % ironMcpVersion,
    "org.http4s"         %% "http4s-ember-client" % http4sVersion
  )
)
lazy val videoView         = view("video", "video-resource-server")
lazy val videoServer       = server("video", "video-resource-server", videoView)
lazy val sayView           = view("say", "say-server")
lazy val sayServer         = server("say", "say-server", sayView).settings(
  libraryDependencies += "org.http4s" %% "http4s-ember-client" % http4sVersion
)
lazy val shadertoyView     = view("shadertoy", "shadertoy-server")
  .dependsOn(shadertoyFacade)
  .settings(
    viewAliases += "shadertoy-lite" -> ((LocalRootProject / baseDirectory).value / "facades" / "shadertoy" / "vendor" / "ShaderToyLite.js").getAbsolutePath
  )
lazy val shadertoyServer   = server("shadertoy", "shadertoy-server", shadertoyView)
lazy val threeView       = view("three", "threejs-server").dependsOn(threeFacade)
lazy val threeServer     = server("three", "threejs-server", threeView)
lazy val wikiModel       = model("wiki", "wiki-explorer-server")
lazy val wikiView        = view("wiki", "wiki-explorer-server").dependsOn(wikiModel.js, forcegraphFacade)
lazy val wikiServer      = server("wiki", "wiki-explorer-server", wikiView).dependsOn(wikiModel.jvm).settings(
  libraryDependencies ++= Seq(
    "org.jsoup"  % "jsoup"               % "1.18.3",
    "org.http4s" %% "http4s-ember-client" % http4sVersion
  )
)
lazy val mapModel         = model("map", "map-server")
lazy val mapView          = view("map", "map-server").dependsOn(mapModel.js)
lazy val mapServer        = server("map", "map-server", mapView).dependsOn(mapModel.jvm).settings(
  libraryDependencies += "org.http4s" %% "http4s-ember-client" % http4sVersion
)
lazy val pdfModel         = model("pdf", "pdf-server")
lazy val pdfView          = view("pdf", "pdf-server").dependsOn(pdfModel.js, pdfjsFacade)
lazy val pdfServer        = server("pdf", "pdf-server", pdfView).dependsOn(pdfModel.jvm).settings(
  libraryDependencies += "org.http4s" %% "http4s-ember-client" % http4sVersion
)

lazy val exampleServers = Seq(basicServer, budgetServer, cohortServer, segmentationServer, debugServer, scenarioServer, monitorServer, sheetMusicServer, transcriptServer, lazyAuthServer, integrationServer, qrServer, quickstartServer, videoServer, sayServer, shadertoyServer, threeServer, wikiServer, mapServer, pdfServer)
lazy val exampleViews   = Seq(basicView, budgetView, cohortView, segmentationView, debugView, scenarioView, monitorView, sheetMusicView, transcriptView, lazyAuthButtonView, lazyAuthSecretView, integrationView, qrView, quickstartView, videoView, sayView, shadertoyView, threeView, wikiView, mapView, pdfView)
lazy val facades: Seq[ProjectReference] = Seq(chartjs, abcjs, webspeech, shadertoyFacade, threeFacade, forcegraphFacade, pdfjsFacade)
lazy val models: Seq[ProjectReference]  = Seq(budgetModel.jvm, budgetModel.js, cohortModel.jvm, cohortModel.js, segmentationModel.jvm, segmentationModel.js, scenarioModel.jvm, scenarioModel.js, monitorModel.jvm, monitorModel.js, wikiModel.jvm, wikiModel.js, mapModel.jvm, mapModel.js, pdfModel.jvm, pdfModel.js)
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
  .settings(name := "iron-mcp-apps")
