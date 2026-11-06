import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("Map Server", () => {
  test("show-map: renders CesiumJS map container and fullscreen button", async ({ page }) => {
    await callTool(page, "CesiumJS Map Server");
    const app = appFrame(page);
    await expect(app.locator("#cesiumContainer")).toBeVisible({ timeout: 30000 });
    await expect(app.locator("#fullscreen-btn")).toBeVisible();
  });
});
