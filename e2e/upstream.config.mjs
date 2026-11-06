// ext-apps' own end-to-end suite (tests/e2e/servers.spec.ts, unmodified),
// run by ext-apps' Playwright against whatever servers basic-host lists.
//
// Run from ~/ext-apps, so the spec and this config share one copy of
// @playwright/test:
//
//   cd ~/ext-apps && npx playwright test -c ~/iron-mcp-ext-apps/e2e/upstream.config.mjs
//
// GOLDENS picks the screenshots to compare against:
//   ts-local  (default) made on this machine from the TypeScript examples
//             (`--update-snapshots` writes them)
//   upstream  the ones checked into ext-apps (made in their Docker image)
//
// The servers are not started here: start basic-host (and the servers it
// lists) first; see e2e/README.md.
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const extApps = process.env.EXT_APPS ?? join(process.env.HOME, "ext-apps");
const { devices } = createRequire(join(extApps, "package.json"))("@playwright/test");

const goldens = process.env.GOLDENS ?? "ts-local";
const snapshotPathTemplate =
  goldens === "upstream"
    ? join(extApps, "tests/e2e/{testFileName}-snapshots/{arg}{ext}")
    : join(here, "goldens", goldens, "{arg}{ext}");

export default {
  testDir: join(extApps, "tests/e2e"),
  testMatch: ["servers.spec.ts"],
  fullyParallel: true,
  workers: Number(process.env.WORKERS ?? 6),
  timeout: 60000,
  reporter: [["list"], ["json", { outputFile: join(here, "results", `${process.env.RUN ?? "run"}.json`) }]],
  outputDir: join(here, "results", "artifacts"),
  snapshotPathTemplate,
  use: {
    baseURL: "http://localhost:8080",
    trace: "off",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  expect: {
    toHaveScreenshot: { maxDiffPixelRatio: 0.06, animations: "disabled" },
  },
};
