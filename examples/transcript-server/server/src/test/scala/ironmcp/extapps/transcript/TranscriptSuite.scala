package ironmcp.extapps.transcript

import io.github.iltotore.iron.*
import ironmcp.apps.*
import ironmcp.protocol.*
import munit.CatsEffectSuite

class TranscriptSuite extends CatsEffectSuite:

  private val meta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities.none
  )

  test("transcript server registers transcribe tool and UI resource with permissions"):
    for
      server <- TranscriptServer.load
      // Check tool exists with UI metadata
      toolsResult <- server.tools.get.list(ListToolsParams(meta))
      transcribeOpt = toolsResult.tools.find(_.name.contains("transcribe"))
      _ = assert(transcribeOpt.isDefined)
      transcribe = transcribeOpt.get
      toolUi = transcribe._meta.flatMap(_.get(McpApps.UiKey)).flatMap(_.as[UiToolMeta].toOption)
      _ = assertEquals(toolUi.flatMap(_.resourceUri), Some(TranscriptServer.ResourceUri: String))

      // Check tool call returns ready status
      callResult <- server.tools.get.call(CallToolParams(name = "transcribe".refineUnsafe, _meta = meta))
      _ = assert(callResult match
        case r: CallToolResult =>
          r.content.headOption.exists {
            case ContentBlock.Text(text, _, _) => text.contains("ready")
            case _                             => false
          }
        case _ => false
      )

      // Check UI resource contains html and declares microphone + clipboard permissions
      resources = server.resources.get
      readResult <- resources.read(ReadResourceParams(uri = (TranscriptServer.ResourceUri: String).assume, _meta = meta))
      resource = readResult match
        case r: ReadResourceResult => r.contents.headOption.get
        case _                     => fail("expected ReadResourceResult")
      _ = assert(resource match
        case t: ResourceContents.Text => t.text.contains("Live Transcript")
        case _                        => false
      )
      resUi = resource match
        case t: ResourceContents.Text => t._meta.flatMap(_.get(McpApps.UiKey)).flatMap(_.as[UiResourceMeta].toOption)
        case _                        => None
      perms = resUi.flatMap(_.permissions)
      _ = assertEquals(perms.flatMap(_.microphone).isDefined, true)
      _ = assertEquals(perms.flatMap(_.clipboardWrite).isDefined, true)
    yield ()
