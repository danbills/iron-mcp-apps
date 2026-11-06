package ironmcp.extapps.three

import cats.effect.IO
import io.circe.Decoder
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, NoArgs, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.{Default, JsonSchemaOf}
import ironmcp.server.*

final case class ShowSceneArgs(
    code: Option[String :| (Default["""const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(75, width / height, 0.1, 1000);
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
renderer.setSize(width, height);
renderer.setClearColor(0x000000, 0);

const cube = new THREE.Mesh(
  new THREE.BoxGeometry(1, 1, 1),
  new THREE.MeshStandardMaterial({ color: 0x00ff88 })
);
cube.rotation.x = 0.5;
cube.rotation.y = 0.7;
scene.add(cube);

const keyLight = new THREE.DirectionalLight(0xffffff, 1.2);
keyLight.position.set(1, 1, 2);
scene.add(keyLight);
const fillLight = new THREE.DirectionalLight(0x8888ff, 0.4);
fillLight.position.set(-1, 0, -1);
scene.add(fillLight);
scene.add(new THREE.AmbientLight(0x404040, 0.5));

camera.position.z = 3;

function animate() {
  requestAnimationFrame(animate);
  cube.rotation.x += 0.01;
  cube.rotation.y += 0.01;
  renderer.render(scene, camera);
}
animate();"""] DescribedAs "JavaScript code to render the 3D scene")] = None,
    height: Option[Int :| (Default[400] & Positive DescribedAs "Height in pixels")] = None
) derives Decoder, JsonSchemaOf

object ThreejsServer:

  val ResourceUri: UiUri = "ui://threejs/mcp-app.html"

  val Documentation: String =
    """# Three.js view Documentation

## Available Globals
- `THREE` - Three.js library (r181)
- `canvas` - Pre-created canvas element
- `width`, `height` - Canvas dimensions in pixels
- `OrbitControls` - Interactive camera controls
- `EffectComposer`, `RenderPass`, `UnrealBloomPass` - Postprocessing

## Example
```js
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(75, width / height, 0.1, 1000);
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
renderer.setSize(width, height);
```"""

  def apply(html: String): McpServer[IO] =
    val showScene = McpTool[ShowSceneArgs](
      "show_threejs_scene",
      "Render an interactive 3D scene with custom Three.js code. Supports transparent backgrounds (alpha: true) for seamless host UI integration. Available globals: THREE, OrbitControls, EffectComposer, RenderPass, UnrealBloomPass, canvas, width, height.",
      title = Some("Show Three.js Scene")
    )(_ =>
      IO.pure(CallToolResult(List(ContentBlock.Text("Three.js scene rendered"))))
    ).withUi(ResourceUri)

    val learnThree = McpTool[NoArgs](
      "learn_threejs",
      "Get documentation and examples for using the Three.js View",
      title = Some("Learn Three.js")
    )(_ =>
      IO.pure(CallToolResult(List(ContentBlock.Text(Documentation))))
    )

    McpServer(
      info = Implementation(name = "Three.js Server", version = "1.0.0"),
      tools = Some(ToolSet.of(showScene, learnThree)),
      resources = Some(
        UiResources.of[IO](
          UiResource(
            ResourceUri,
            "Three.js View UI",
            html,
            Some("Three.js Interactive 3D Viewer")
          )
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("threejs-server/view").map(apply)

object Main extends ExampleMain("threejs-server", _ => ThreejsServer.load)
