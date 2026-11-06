import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("Video Resource Server", () => {
  test("play_video: the View reads the videos:// resource through the host and plays it", async ({ page }) => {
    await callTool(page, "Video Resource Server");
    const app = appFrame(page);
    await expect(app.locator("#player")).toBeVisible({ timeout: 30000 });
    await expect(app.locator("#video-info")).toHaveText("Loaded via MCP resource (1MB)");
    expect(await app.locator("#video").getAttribute("src")).toMatch(/^blob:/);
    // The picker lists the server's videos (resources/list through the host).
    await app.locator("#change-video-btn").click();
    await expect(app.locator("#video-picker option")).toHaveCount(7);
    await expect(app.locator("#video-picker option").nth(1)).toHaveText("video-bunny-1mb");
  });
});
