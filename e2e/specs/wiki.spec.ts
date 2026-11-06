import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("Wiki Explorer Server", () => {
  test("get-first-degree-links: renders interactive force graph and controls", async ({ page }) => {
    await callTool(page, "Wiki Explorer");
    const app = appFrame(page);
    await expect(app.locator("#graph")).toBeVisible({ timeout: 30000 });
    await expect(app.locator("#controls")).toBeVisible();
    await expect(app.locator("#reset-graph")).toBeVisible();
    await expect(app.locator("#zoom-in")).toBeVisible();
    await expect(app.locator("#zoom-out")).toBeVisible();
  });
});
