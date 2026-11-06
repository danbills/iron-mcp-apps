import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("ShaderToy Server", () => {
  test("render-shadertoy: renders WebGL shader canvas and controls", async ({ page }) => {
    await callTool(page, "ShaderToy Server");
    const app = appFrame(page);
    await expect(app.locator("#canvas")).toBeVisible({ timeout: 30000 });
    await expect(app.locator("#main-el")).toBeVisible();
    await expect(app.locator("#fullscreen-btn")).toBeVisible();
  });
});
