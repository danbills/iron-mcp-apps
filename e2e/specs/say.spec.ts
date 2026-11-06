import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("Say Demo", () => {
  test("say: opens the text-to-speech player with text display and controls", async ({ page }) => {
    await callTool(page, "Say Demo");
    const app = appFrame(page);
    await expect(app.locator("#main-container")).toBeVisible({ timeout: 30000 });
    await expect(app.locator("#text-display")).toBeVisible();
    await expect(app.locator("#play-pause-btn")).toBeVisible();
    await expect(app.locator("#restart-btn")).toBeVisible();
    await expect(app.locator("#fullscreen-btn")).toBeVisible();
    await expect(app.locator("#info-btn")).toBeVisible();
  });
});
