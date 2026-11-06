package ironmcp.extapps.three

import io.github.iltotore.iron.*
import ironmcp.apps.*
import ironmcp.protocol.*
import munit.CatsEffectSuite

class ThreejsSuite extends CatsEffectSuite:

  private val meta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities.none
  )

  test("threejs server registers show_threejs_scene and learn_threejs tools"):
    for
      server <- ThreejsServer.load
      toolsResult <- server.tools.get.list(ListToolsParams(meta))
      showOpt = toolsResult.tools.find(_.name.contains("show_threejs_scene"))
      _ = assert(showOpt.isDefined)
      showTool = showOpt.get
      toolUi = showTool._meta.flatMap(_.get(McpApps.UiKey)).flatMap(_.as[UiToolMeta].toOption)
      _ = assertEquals(toolUi.flatMap(_.resourceUri), Some(ThreejsServer.ResourceUri: String))

      learnOpt = toolsResult.tools.find(_.name.contains("learn_threejs"))
      _ = assert(learnOpt.isDefined)

      // Check tool call returns success
      callResult <- server.tools.get.call(CallToolParams(name = "show_threejs_scene".refineUnsafe, _meta = meta))
      _ = assert(callResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, _) => text.contains("Three.js scene rendered")
            case _                             => false
          }
        case _ => false
      )

      // Check learn_threejs tool call returns documentation
      learnResult <- server.tools.get.call(CallToolParams(name = "learn_threejs".refineUnsafe, _meta = meta))
      _ = assert(learnResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, _) => text.contains("Three.js") && text.contains("OrbitControls")
            case _                             => false
          }
        case _ => false
      )

      // Check UI resource contains bundled HTML
      resources = server.resources.get
      readResult <- resources.read(ReadResourceParams(uri = (ThreejsServer.ResourceUri: String).assume, _meta = meta))
      resource = readResult match
        case r: ReadResourceResult => r.contents.headOption.get
        case _                     => fail("expected ReadResourceResult")
      _ = assert(resource match
        case t: ResourceContents.Text => t.text.contains("Three.js") && t.text.contains("canvas")
        case _                        => false
      )
    yield ()
