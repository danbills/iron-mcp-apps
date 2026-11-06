package ironmcp.extapps

import cats.effect.IO
import cats.syntax.all.*
import ironmcp.apps.UiHtml

import scala.io.{Codec, Source}

/** A View bundled by the build (ViewBundlePlugin) into `views/<example>/<view>/`:
  * its page, with its styles and script inline, since a View is one
  * self-contained document.
  */
object ViewPage:

  def load(path: String): IO[String] =
    (read(s"views/$path/mcp-app.html"), read(s"views/$path/view.css"), read(s"views/$path/view.js"))
      .mapN((html, css, js) => UiHtml.inlineScript(UiHtml.inlineStyle(html, css), js))

  private def read(resource: String): IO[String] =
    IO.blocking(Option(getClass.getClassLoader.getResource(resource)))
      .flatMap(url => IO.fromOption(url)(new IllegalStateException(s"missing resource $resource: was the View bundled?")))
      .flatMap(url => IO.blocking(Source.fromURL(url)(using Codec.UTF8)).bracket(s => IO.blocking(s.mkString))(s => IO.blocking(s.close())))
