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
