import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("Transcript Server", () => {
  test("transcribe: opens the live transcript UI with controls", async ({ page }) => {
    await callTool(page, "Transcript Server");
    const app = appFrame(page);
    await expect(app.locator("#transcript")).toBeVisible({ timeout: 30000 });
    await expect(app.locator(".transcript-placeholder")).toHaveText("Your speech will appear here...");
    await expect(app.locator("#start-btn")).toBeVisible();
    await expect(app.locator("#timer")).toHaveText("0:00");
    await expect(app.locator("#send-btn")).toBeDisabled();
  });
});
