import { expect, type Page, type ConsoleMessage } from "@playwright/test";

/** The View's frame: page > sandbox iframe > app iframe (as upstream's servers.spec.ts). */
export function appFrame(page: Page) {
  return page.frameLocator("iframe").first().frameLocator("iframe").first();
}

/** Selects a server by its MCP name, picks a tool (or the default one) and calls it. */
export async function callTool(page: Page, serverName: string, tool?: string, input?: object) {
  await page.goto("/?theme=hide");
  await expect(page.locator("select").first()).toBeEnabled({ timeout: 30000 });
  await page.locator("select").first().selectOption({ label: serverName });
  if (tool) await page.locator("select").nth(1).selectOption({ label: tool });
  if (input) await page.locator("textarea").first().fill(JSON.stringify(input));
  await page.click('button:has-text("Call Tool")');
  await expect(page.frameLocator("iframe").first().locator("iframe")).toBeVisible({ timeout: 30000 });
}

/** Console lines from the host page, which logs what the View asked of it. */
export function hostLogs(page: Page): string[] {
  const logs: string[] = [];
  page.on("console", (msg: ConsoleMessage) => {
    if (msg.text().includes("[HOST]")) logs.push(msg.text());
  });
  return logs;
}

import { readFileSync } from "node:fs";
import { join } from "node:path";

/** The examples' MCP URLs by name, as scripts/e2e.sh wrote them. */
function serverUrl(example: string): string {
  const servers = JSON.parse(readFileSync(join(__dirname, "..", "host", "dist", "servers.json"), "utf-8"));
  const url = servers[example];
  if (!url) throw new Error(`no server ${example} in servers.json`);
  return url;
}

/**
 * Opens the AppBridge test host (e2e/host) on one example's tool and waits
 * until the View has its result. Then `page.evaluate(() => harness....)`.
 */
export async function openHarness(page: Page, example: string, tool: string, args: object = {}, theme = "light") {
  const q = new URLSearchParams({ server: serverUrl(example), tool, args: JSON.stringify(args), theme });
  await page.goto(`http://localhost:8095/index.html?${q}`);
  await page.waitForFunction(() => (window as any).harness?.ready || (window as any).harnessError, null, { timeout: 30000 });
  const error = await page.evaluate(() => (window as any).harnessError);
  if (error) throw new Error(`harness: ${error}`);
}

/** The View's frame inside the test host. */
export function harnessFrame(page: Page) {
  return page.frameLocator("iframe").first();
}

/** Calls one of the View's own tools through the host (AppBridge.callTool). */
export async function callAppTool(page: Page, name: string, args: object = {}): Promise<any> {
  return page.evaluate(([n, a]) => (window as any).harness.callAppTool(n, a), [name, args] as const);
}

/** What the View asked of the host, by kind (message, open-link, update-model-context, ...). */
export async function hostEvents(page: Page, kind?: string): Promise<any[]> {
  const all = await page.evaluate(() => (window as any).harness.events);
  return kind ? all.filter((e: any) => e.kind === kind).map((e: any) => e.params) : all;
}
