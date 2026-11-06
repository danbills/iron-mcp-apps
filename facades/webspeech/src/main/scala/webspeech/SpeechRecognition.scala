package webspeech

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSBracketAccess

/** The Web Speech API's recognition side (`SpeechRecognition`, or
  * `webkitSpeechRecognition` in Chromium), which scalajs-dom does not cover.
  */
@js.native
trait SpeechRecognition extends dom.EventTarget:
  var continuous: Boolean                                   = js.native
  var interimResults: Boolean                               = js.native
  var lang: String                                          = js.native
  var onstart: js.Function1[dom.Event, Any]                 = js.native
  var onresult: js.Function1[SpeechRecognitionEvent, Any]   = js.native
  var onerror: js.Function1[SpeechRecognitionErrorEvent, Any] = js.native
  var onend: js.Function1[dom.Event, Any]                   = js.native
  def start(): Unit                                         = js.native
  def stop(): Unit                                          = js.native

object SpeechRecognition:
  /** A recognizer, if this browser has one. */
  def apply(): Option[SpeechRecognition] =
    val ctor =
      if !js.isUndefined(js.Dynamic.global.SpeechRecognition) then js.Dynamic.global.SpeechRecognition
      else js.Dynamic.global.webkitSpeechRecognition
    Option.when(!js.isUndefined(ctor))(js.Dynamic.newInstance(ctor)().asInstanceOf[SpeechRecognition])

@js.native
trait SpeechRecognitionEvent extends dom.Event:
  val results: SpeechRecognitionResultList = js.native

@js.native
trait SpeechRecognitionErrorEvent extends dom.Event:
  val error: String = js.native

@js.native
trait SpeechRecognitionResultList extends js.Object:
  val length: Int = js.native
  @JSBracketAccess def apply(index: Int): SpeechRecognitionResult = js.native

@js.native
trait SpeechRecognitionResult extends js.Object:
  val isFinal: Boolean = js.native
  val length: Int      = js.native
  @JSBracketAccess def apply(index: Int): SpeechRecognitionAlternative = js.native

@js.native
trait SpeechRecognitionAlternative extends js.Object:
  val transcript: String = js.native
  val confidence: Double = js.native
