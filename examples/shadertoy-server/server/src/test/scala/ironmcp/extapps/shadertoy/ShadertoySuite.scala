package ironmcp.extapps.shadertoy

import io.github.iltotore.iron.*
import ironmcp.apps.*
import ironmcp.protocol.*
import munit.CatsEffectSuite

class ShadertoySuite extends CatsEffectSuite:

  private val meta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities.none
  )

  test("shadertoy server registers render-shadertoy tool and UI resource"):
    for
      server <- ShadertoyServer.load
      toolsResult <- server.tools.get.list(ListToolsParams(meta))
      renderOpt = toolsResult.tools.find(_.name.contains("render-shadertoy"))
      _ = assert(renderOpt.isDefined)
      renderTool = renderOpt.get
      toolUi = renderTool._meta.flatMap(_.get(McpApps.UiKey)).flatMap(_.as[UiToolMeta].toOption)
      _ = assertEquals(toolUi.flatMap(_.resourceUri), Some(ShadertoyServer.ResourceUri: String))

      // Check tool call returns success
      callResult <- server.tools.get.call(CallToolParams(name = "render-shadertoy".refineUnsafe, _meta = meta))
      _ = assert(callResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, _) => text.contains("Shader rendered successfully")
            case _                             => false
          }
        case _ => false
      )

      // Check UI resource contains bundled HTML
      resources = server.resources.get
      readResult <- resources.read(ReadResourceParams(uri = (ShadertoyServer.ResourceUri: String).assume, _meta = meta))
      resource = readResult match
        case r: ReadResourceResult => r.contents.headOption.get
        case _                     => fail("expected ReadResourceResult")
      _ = assert(resource match
        case t: ResourceContents.Text => t.text.contains("ShaderToy") && t.text.contains("canvas")
        case _                        => false
      )
    yield ()
