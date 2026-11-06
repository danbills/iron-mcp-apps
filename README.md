# iron-mcp-apps

Strictly typed Scala 3 & Scala.js implementations of Model Context Protocol (MCP) Apps, ported from [`@modelcontextprotocol/ext-apps`](https://github.com/modelcontextprotocol/ext-apps).

Powered by [`iron-mcp`](https://github.com/danbills/iron-mcp).

## Architecture

- **Server-Side (`modules/protocol`, `iron-mcp-core`, `iron-mcp-http`, `iron-mcp-apps`)**: Pure functional Scala 3 servers built with Cats Effect and http4s. Pure JSON-RPC 2.0 with compile-time schema derivation from Iron refinement types and zero runtime reflection.
- **Client Views (`iron-mcp-apps-view`)**: Interactive user interfaces written in Scala.js, running in isolated iframes and communicating with the host via JSON-RPC over `postMessage`.
- **Packaging (`ViewBundlePlugin`)**: HTML, CSS, and Scala.js bundles compiled with `esbuild` and packaged directly as managed server resources.

## Example Servers & Views

All 20 MCP App examples ported and verified:

| Server | Category | Description & Stack |
|---|---|---|
| `basic-server` | Core | Unified implementation of upstream framework variants (Preact, React, Solid, Svelte, Vanilla JS, Vue) |
| `budget-allocator-server` | Data Viz | Dynamic budget allocation sliders and Chart.js doughnut visualization |
| `cohort-heatmap-server` | Data Viz | Interactive user retention heatmap matrix with dynamic metric toggling |
| `customer-segmentation-server` | Data Viz | 2D customer segmentation bubble chart using Chart.js |
| `debug-server` | DevTools | Diagnostic inspector for host-view communication and capabilities |
| `integration-server` | Protocol | Complete protocol coverage: message sending, logging, and link opening |
| `qr-server` | Utility | QR code generator producing PNG image blocks via ZXing |
| `quickstart` | Demo | Minimal reference example for creating an MCP App |
| `scenario-modeler-server` | Simulation | SaaS revenue & financial projection modeling with Chart.js |
| `sheet-music-server` | Audio | ABC notation parser in GraalJS with ABCJS rendering and audio synthesizer playback |
| `system-monitor-server` | System | Real-time CPU, memory, and OS statistics via OSHI with stacked area charts |
| `transcript-server` | Speech | Web Audio and Web Speech live microphone transcription |
| `lazy-auth-server` | Auth | Step-up OAuth 2.1 authorization with RFC 9728 metadata and Keycloak coordination |
| `say-server` | Speech | Text-to-speech audio player with Pocket TTS integration |
| `shadertoy-server` | WebGL | Interactive fragment shader previewer running ShaderToyLite on WebGL |
| `threejs-server` | 3D Graphics | Interactive 3D scene renderer using Three.js (r181) |
| `wiki-explorer-server` | Graph Viz | 3D force-directed Wikipedia link explorer with Jsoup parsing |
| `map-server` | Geospatial | 3D interactive CesiumJS globe with OpenStreetMap and Nominatim rate-limited geocoding |
| `pdf-server` | Documents | Chunked byte-range PDF streaming with PDF.js viewer and AI command bridge |
| `video-resource-server` | Media | Video player reading and rendering `videos://` MCP resources |

## Development & Testing

### Prerequisites
- Java 21+
- sbt 2.x
- Node.js & npm (for esbuild and Playwright)

### Build & Run Unit Tests
```bash
# Run unit tests across all ported servers
sbt "Test/testFull"
```

### Run End-to-End Tests
```bash
# Boot all servers concurrently and run Playwright E2E suite
./scripts/e2e.sh --project=ports
```

### Launch All Servers
```bash
# Starts all 20 servers on ports 3201–3220
./scripts/run-all.sh
```

## License

Apache-2.0
