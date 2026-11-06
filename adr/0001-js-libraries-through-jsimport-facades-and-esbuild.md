# 0001. JS Libraries Come In Through `@JSImport` Facades, Bundled by esbuild (`ViewBundlePlugin`)

* **Status:** Accepted
* **Date:** 2026-11-06
* **Context for:** every View in this repo, and the iron-mcp-apps-view guide's packaging advice

## Context

**A View is one self-contained HTML document.** An MCP Apps host renders it in a sandboxed iframe. The
default sandbox CSP loads nothing from the network, so everything the View runs must be inline. A View can
declare `_meta.ui.csp.resourceDomains` to load from a CDN, but upstream's examples do this only where the
library itself loads assets from its own origin (Cesium).

**The Views here need npm libraries.** Upstream's examples bundle all of them with Vite and
`vite-plugin-singlefile`:

| Library | Version (upstream's lock) | Used by | Global (UMD/IIFE) build? |
|---|---|---|---|
| `three` | 0.181.2 | threejs | **No**: ES modules only since r160 |
| `chart.js` | 4.5.1 | budget-allocator, customer-segmentation, scenario-modeler, system-monitor | Yes (`chart.umd.js`) |
| `force-graph`, `d3-force-3d` | 1.51.0, 3.0.6 | wiki-explorer | Yes |
| `abcjs` | 6.6.0 | sheet-music | Yes |
| `pdfjs-dist` (+ its worker) | 5.4.530 | pdf | **No**: ES modules only (`.mjs`) |
| `@cantoo/pdf-lib` | 2.6.5 | pdf | Yes |
| ShaderToyLite (vendored) | n/a | shadertoy | No: one ES module file |

**The Scala.js linker doesn't bundle.** With `ModuleKind.ESModule`, the output keeps `import "three"`
statements for something else to resolve. With `NoModule`, it can only use globals that are already on the
page.

## Decision

1. **Facades use `@JSImport`** on the npm package, by the name and path the package exports
   (`@JSImport("three", JSImport.Namespace)`, `@JSImport("chart.js", "Chart")`).
   - Vendored files get an esbuild alias.
   - Each facade is hand-written, typed, and covers only what the Views use. `js.Dynamic` appears only where
     the JS API is open-ended: Chart.js option bags, user code run against the three namespace.
   - Facades live in `facades/<lib>`.
2. **Versions are pinned** in `package.json` and `package-lock.json`, matching upstream's lock, so a View
   and its TypeScript original run the same library code.
3. **Every View links as an ES module** (`ViewBundlePlugin`). esbuild then bundles the linked `main.js` and
   everything it imports into one minified classic script (`--bundle --format=iife --minify`). The View's CSS
   entry (`src/main/view/view.css`) and what it `@import`s, including a library's own CSS from
   `node_modules`, go into one stylesheet.
   - The `viewBundle` task runs from sbt (`Def.uncached`), after `fullLinkJS`.
   - A server's `resourceGenerators` copies the bundle into its resources.
4. **The server inlines both** into the page at runtime (`UiHtml.inlineStyle`, then `UiHtml.inlineScript`).
5. **pdf.js's worker** is bundled separately and handed to pdf.js as a `data:text/javascript` URL, exactly as
   upstream's Vite build does (`GlobalWorkerOptions.workerSrc`).
6. **Cesium stays a runtime CDN global** (`@JSGlobal("Cesium")`), loaded the way upstream loads it. It
   fetches its workers and assets from its own base URL, which a bundle can't replace.

## Consequences

- **Builds need Node.** Run `npm ci` before sbt. The iron-mcp-apps-view tests already need Node to run, so
  this adds no new tool.
- **Bundles are small.** esbuild's `--minify` takes the Scala.js output from about 2.9 MB to about 1.1 MB
  (quickstart). That solves the size problem of iron-mcp#1 for bundled Views, whatever the linker's own
  minifier does.
- **Facades are partial.** A View that needs more of a library extends its facade first. A missing member is
  then a compile error, not a runtime `undefined`.
- **The same `@JSImport` facades would work** with any bundler (Vite, webpack) or none, in a Node test.

## Alternatives considered

- **`@JSGlobal` facades over UMD builds inlined from WebJars.** No npm in the build. But three.js 0.181 and
  pdf.js 5 ship no global build, so both would need either an old version (diverging from upstream) or a
  prebundle step anyway. Rejected.
- **ScalablyTyped-generated facades.** Complete and typed, but large, slow to generate, and not known to
  work with sbt 2. A View uses a small slice of each library. Rejected.
- **Vite with `@scala-js/vite-plugin-scalajs`**, as upstream builds its Views. Vite would drive sbt
  (`sbt fastLinkJSOutput`) while sbt also needs the bundle as a server resource: a build calling itself.
  Rejected; esbuild is what Vite uses underneath anyway.
- **scalajs-bundler.** Built for webpack and sbt 1. Rejected.
