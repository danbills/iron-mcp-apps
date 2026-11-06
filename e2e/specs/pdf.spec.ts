import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("PDF Server", () => {
  test("display_pdf: renders PDF viewer toolbar and canvas container", async ({ page }) => {
    await callTool(page, "PDF Server");
    const app = appFrame(page);
    await expect(app.locator(".main")).toBeVisible({ timeout: 30000 });
    await expect(app.locator("#pdf-canvas")).toBeAttached();
  });
});
