package ironmcp.extapps.view

import cats.effect.IO
import org.scalajs.dom

import scala.scalajs.js

/** Console logging, as upstream's Views do (`console.log.bind(console, "[APP]")`). */
final class Log(prefix: Option[String]):
  private def parts(values: Seq[Any]): Seq[js.Any] =
    val all = prefix.toList ++ values
    (if all.isEmpty then Seq("") else all).map(value => value.toString: js.Any)

  def info(values: Any*): IO[Unit]  = IO { val all = parts(values); dom.console.log(all.head, all.tail*) }
  def warn(values: Any*): IO[Unit]  = IO { val all = parts(values); dom.console.warn(all.head, all.tail*) }
  def error(values: Any*): IO[Unit] = IO { val all = parts(values); dom.console.error(all.head, all.tail*) }

object Log:
  def apply(prefix: String): Log = new Log(Some(prefix))
  val plain: Log                 = new Log(None)
