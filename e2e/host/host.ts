/**
 * A minimal MCP Apps host for the ports' own e2e specs, on ext-apps' AppBridge
 * (the host side of the protocol, as basic-host uses it). Unlike basic-host it
 * exposes the bridge to the test (window.harness): to call the View's tools,
 * change the host context, tear the View down, and see every request the View
 * made of the host.
 *
 *   host.html?server=<mcp url>&tool=<name>&args=<json>&theme=light|dark
 */
import { AppBridge, PostMessageTransport, getToolUiResourceUri } from "@modelcontextprotocol/ext-apps/app-bridge";
import { Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";

type Recorded = { kind: string; params: unknown };
const events: Recorded[] = [];
function record(kind: string, params: unknown) {
  events.push({ kind, params });
  console.log("[HARNESS]", kind, JSON.stringify(params)?.slice(0, 500));
}

async function main() {
  const q = new URLSearchParams(location.search);
  const server = q.get("server")!;
  const tool = q.get("tool")!;
  const args = JSON.parse(q.get("args") ?? "{}");
  const info = { name: "Harness Host", version: "1.0.0" };

  const client = new Client(info, { versionNegotiation: { mode: "auto" } });
  await client.connect(new StreamableHTTPClientTransport(new URL(server)));
  const definition = (await client.listTools()).tools.find((t) => t.name === tool);
  if (!definition) throw new Error(`no tool ${tool}`);
  const uri = getToolUiResourceUri(definition);
  if (!uri) throw new Error(`${tool} has no View`);
  const content = (await client.readResource({ uri })).contents[0] as { text?: string; blob?: string };
  const html = content.blob ? atob(content.blob) : content.text!;

  const iframe = document.createElement("iframe");
  iframe.setAttribute("sandbox", "allow-scripts allow-same-origin allow-forms");
  iframe.style.height = "600px";
  document.getElementById("app")!.appendChild(iframe);

  const caps = client.getServerCapabilities();
  const bridge = new AppBridge(
    client,
    info,
    {
      openLinks: {},
      downloadFile: {},
      serverTools: caps?.tools,
      serverResources: caps?.resources,
      logging: {},
      updateModelContext: { text: {}, image: {}, structuredContent: {} },
      message: { text: {}, image: {} },
    },
    {
      hostContext: {
        theme: (q.get("theme") as "light" | "dark") ?? "light",
        platform: "web",
        locale: "en-US",
        timeZone: "UTC",
        containerDimensions: { maxHeight: 6000 },
        displayMode: "inline",
        availableDisplayModes: ["inline", "fullscreen"],
        toolInfo: { id: 1, tool: definition },
      },
    },
  );
  bridge.onmessage = async (p) => (record("message", p), {});
  bridge.onopenlink = async (p) => (record("open-link", p), {});
  bridge.ondownloadfile = async (p) => (record("download-file", p), {});
  bridge.onupdatemodelcontext = async (p) => (record("update-model-context", p), {});
  bridge.onrequestdisplaymode = async (p) => {
    record("request-display-mode", p);
    bridge.sendHostContextChange({ displayMode: p.mode });
    return { mode: p.mode };
  };
  bridge.onloggingmessage = (p) => record("log", p);
  bridge.onsizechange = async (p) => {
    record("size", p);
    if (p.height) iframe.style.height = `${p.height}px`;
  };
  const initialized = new Promise<void>((resolve) => {
    bridge.oninitialized = () => (record("initialized", {}), resolve());
  });

  // Listen before the View loads, so its ui/initialize is not missed.
  await bridge.connect(new PostMessageTransport(iframe.contentWindow!, iframe.contentWindow!));
  iframe.srcdoc = html;
  await initialized;

  bridge.sendToolInput({ arguments: args });
  const result = await client.callTool({ name: tool, arguments: args });
  record("tool-result", result);
  bridge.sendToolResult(result);

  (window as any).harness = {
    events,
    listAppTools: () => bridge.listTools({}),
    callAppTool: (name: string, a: object = {}) => bridge.callTool({ name, arguments: a }),
    hostContext: (ctx: object) => bridge.sendHostContextChange(ctx),
    teardown: () => bridge.teardownResource({}),
    ready: true,
  };
}

main().catch((e) => {
  console.error("[HARNESS] failed", e);
  (window as any).harnessError = String(e?.stack ?? e);
});
