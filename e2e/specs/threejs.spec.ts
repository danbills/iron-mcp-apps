import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("Three.js Server", () => {
  test("show_threejs_scene: renders interactive 3D canvas and controls", async ({ page }) => {
    await callTool(page, "Three.js Server");
    const app = appFrame(page);
    await expect(app.locator("#threejs-canvas")).toBeVisible({ timeout: 30000 });
    await expect(app.locator("#container")).toBeVisible();
    await expect(app.locator("#fullscreen-btn")).toBeVisible();
  });
});
