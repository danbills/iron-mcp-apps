package ironmcp.extapps.view

import org.scalajs.dom

/** Element lookups, as upstream's `document.getElementById(...)!`. */
object Dom:

  def byId[A <: dom.Element](id: String): A =
    Option(dom.document.getElementById(id)).getOrElse(throw new NoSuchElementException(s"#$id")).asInstanceOf[A]

  def query[A <: dom.Element](selector: String): A =
    Option(dom.document.querySelector(selector)).getOrElse(throw new NoSuchElementException(selector)).asInstanceOf[A]

  def queryAll[A <: dom.Element](selector: String): List[A] =
    val nodes = dom.document.querySelectorAll(selector)
    (0 until nodes.length).map(i => nodes(i).asInstanceOf[A]).toList

  /** A new element with text (never HTML) and classes. */
  def element[A <: dom.Element](tag: String, text: String = "", classes: String = ""): A =
    val el = dom.document.createElement(tag)
    if text.nonEmpty then el.textContent = text
    if classes.nonEmpty then el.setAttribute("class", classes)
    el.asInstanceOf[A]
