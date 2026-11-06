package ironmcp.extapps.shadertoy

import cats.effect.IO
import io.circe.Decoder
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.apps.*
import ironmcp.extapps.{ExampleMain, ViewPage}
import ironmcp.protocol.*
import ironmcp.schema.{Default, JsonSchemaOf}
import ironmcp.server.*

final case class RenderShaderArgs(
    fragmentShader: Option[String :| (Default["""void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    fragColor = vec4(uv, 0.5 + 0.5*sin(iTime), 1.0);
}"""] DescribedAs "Main Image shader - ShaderToy GLSL code")] = None,
    common: Option[String :| (Not[Empty] DescribedAs "Common code shared across all shaders (optional)")] = None,
    bufferA: Option[String :| (Not[Empty] DescribedAs "Buffer A shader code - accessible as iChannel0 (optional)")] = None,
    bufferB: Option[String :| (Not[Empty] DescribedAs "Buffer B shader code - accessible as iChannel1 (optional)")] = None,
    bufferC: Option[String :| (Not[Empty] DescribedAs "Buffer C shader code - accessible as iChannel2 (optional)")] = None,
    bufferD: Option[String :| (Not[Empty] DescribedAs "Buffer D shader code - accessible as iChannel3 (optional)")] = None
) derives Decoder, JsonSchemaOf

object ShadertoyServer:

  val ResourceUri: UiUri = "ui://shadertoy/mcp-app.html"

  val DefaultFragmentShader: String =
    """void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    fragColor = vec4(uv, 0.5 + 0.5*sin(iTime), 1.0);
}"""

  val ToolDescription: String =
    """Renders a ShaderToy-compatible GLSL fragment shader in real-time using WebGL 2.0.

SHADER FORMAT (ShaderToy conventions):
Use ShaderToy's mainImage entry point - do NOT use generic GLSL conventions.

  void mainImage(out vec4 fragColor, in vec2 fragCoord) {
      vec2 uv = fragCoord / iResolution.xy;
      fragColor = vec4(uv, 0.5 + 0.5*sin(iTime), 1.0);
  }

Do NOT use: void main(), gl_FragColor, gl_FragCoord - these will not work.

AVAILABLE UNIFORMS:
- iResolution (vec3): viewport resolution in pixels
- iTime (float): elapsed time in seconds
- iTimeDelta (float): time since last frame
- iFrame (int): frame counter
- iMouse (vec4): mouse/touch position in pixels
- iDate (vec4): year, month, day, seconds
- iChannel0-3 (sampler2D): buffer inputs for multi-pass shaders

The widget is interactive and exposes tools for updating shader source code and querying compilation status. Compilation errors are sent to model context automatically."""

  def apply(html: String): McpServer[IO] =
    val render = McpTool[RenderShaderArgs](
      "render-shadertoy",
      ToolDescription.assume,
      title = Some("ShaderToy Renderer")
    )(_ =>
      IO.pure(CallToolResult(List(ContentBlock.Text("Shader rendered successfully"))))
    ).withUi(ResourceUri)

    McpServer(
      info = Implementation(name = "ShaderToy Server", version = "1.0.0"),
      tools = Some(ToolSet.of(render)),
      resources = Some(
        UiResources.of[IO](
          UiResource(
            ResourceUri,
            "ShaderToy Renderer",
            html,
            Some("ShaderToy WebGL Renderer")
          )
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def load: IO[McpServer[IO]] = ViewPage.load("shadertoy-server/view").map(apply)

object Main extends ExampleMain("shadertoy-server", _ => ShadertoyServer.load)
