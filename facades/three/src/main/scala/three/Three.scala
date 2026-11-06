package three

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Three.js r181 facade for dynamic code evaluation in threejs-server. */
object Three:
  @js.native
  @JSImport("three", JSImport.Namespace)
  val THREE: js.Dynamic = js.native

  @js.native
  @JSImport("three/examples/jsm/controls/OrbitControls.js", "OrbitControls")
  val OrbitControls: js.Dynamic = js.native

  @js.native
  @JSImport("three/examples/jsm/postprocessing/EffectComposer.js", "EffectComposer")
  val EffectComposer: js.Dynamic = js.native

  @js.native
  @JSImport("three/examples/jsm/postprocessing/RenderPass.js", "RenderPass")
  val RenderPass: js.Dynamic = js.native

  @js.native
  @JSImport("three/examples/jsm/postprocessing/UnrealBloomPass.js", "UnrealBloomPass")
  val UnrealBloomPass: js.Dynamic = js.native

  def context: js.Dynamic =
    js.Dynamic.literal(
      THREE = THREE,
      OrbitControls = OrbitControls,
      EffectComposer = EffectComposer,
      RenderPass = RenderPass,
      UnrealBloomPass = UnrealBloomPass
    )
