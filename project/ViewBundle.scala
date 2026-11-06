import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbt.*
import sbt.Keys.*

import scala.sys.process.{Process, ProcessLogger}

/** A Scala.js View, bundled for MCP Apps (adr/0001).
  *
  * The View links as an ES module, so its facades can `@JSImport` npm
  * packages. esbuild (from the repo's `node_modules`) then bundles the linked
  * module and everything it imports into one minified classic script, and the
  * View's CSS entry, with what it `@import`s, into one stylesheet. The server
  * inlines both into the page (`UiHtml.inlineStyle`, `UiHtml.inlineScript`),
  * since a View is one self-contained HTML document.
  *
  * A View project keeps its page and CSS entry in `src/main/view/`:
  * `mcp-app.html` and `view.css`.
  */
object ViewBundlePlugin extends AutoPlugin:
  override def requires = ScalaJSPlugin
  override def trigger  = noTrigger

  object autoImport:
    val viewSource  = settingKey[File]("The directory holding the View's page (mcp-app.html) and CSS entry (view.css)")
    val viewAliases = settingKey[Map[String, String]]("esbuild --alias entries: import name -> path, for vendored JS")
    val viewBundle  = taskKey[File]("Links and bundles the View; the directory with mcp-app.html, view.js and view.css")

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    viewSource  := (Compile / sourceDirectory).value / "view",
    viewAliases := Map.empty,
    viewBundle := Def.uncached {
      val log     = streams.value.log
      val _       = (Compile / fullLinkJS).value
      val linked  = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value / "main.js"
      val root    = (LocalRootProject / baseDirectory).value
      val esbuild = root / "node_modules" / ".bin" / "esbuild"
      val source  = viewSource.value
      val out     = target.value / "view-bundle"
      if !esbuild.exists then throw new MessageOnlyException(s"$esbuild is missing: run `npm ci` in $root first")
      IO.delete(out)
      IO.createDirectory(out)

      def run(args: String*): Unit =
        val code = Process(esbuild.getPath +: args, root).!(ProcessLogger(line => log.info(line), line => log.warn(line)))
        if code != 0 then throw new MessageOnlyException(s"esbuild failed ($code): ${args.mkString(" ")}")

      val aliases = viewAliases.value.toSeq.sorted.map((name, path) => s"--alias:$name=$path")
      run(Seq(linked.getPath, "--bundle", "--format=iife", "--minify", "--log-level=warning", s"--outfile=${out / "view.js"}") ++ aliases*)

      val css = source / "view.css"
      if css.exists then
        run(
          css.getPath,
          "--bundle",
          "--minify",
          "--log-level=warning",
          "--loader:.woff=dataurl",
          "--loader:.woff2=dataurl",
          "--loader:.ttf=dataurl",
          "--loader:.svg=dataurl",
          "--loader:.png=dataurl",
          "--loader:.gif=dataurl",
          s"--outfile=${out / "view.css"}"
        )
      else IO.write(out / "view.css", "")

      IO.copyFile(source / "mcp-app.html", out / "mcp-app.html")
      out
    }
  )
