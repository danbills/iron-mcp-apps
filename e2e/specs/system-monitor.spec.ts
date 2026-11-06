import { test, expect } from "@playwright/test";
import { callTool, appFrame } from "./helpers";

// Upstream's suite masks the chart, memory and uptime; check they fill in and the poll toggles.
test("System Monitor: polls the app-only tool, fills memory and uptime, stops and starts", async ({ page }) => {
  await callTool(page, "System Monitor Server");
  const app = appFrame(page);
  await expect(app.locator("#info-platform")).toHaveText(/^(linux|darwin|win32) (x64|arm64)$/, { timeout: 30000 });
  await expect(app.locator("#memory-percent")).toHaveText(/^\d+%$/);
  await expect(app.locator("#memory-detail")).toHaveText(/ GB \/ .* GB$/);
  await expect(app.locator("#poll-toggle-btn")).toHaveText("Stop");
  await expect(app.locator("#status-text")).toHaveText(/^\d{2}:\d{2}:\d{2}$/);
  await app.locator("#poll-toggle-btn").click();
  await expect(app.locator("#status-text")).toHaveText("Stopped");
  await app.locator("#poll-toggle-btn").click();
  await expect(app.locator("#poll-toggle-btn")).toHaveText("Stop");
});
