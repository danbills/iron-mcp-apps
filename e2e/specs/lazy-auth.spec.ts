import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("Lazy Auth Server", () => {
  test("show_auth_button: renders the public app with Auth me and Revoke token buttons without credentials", async ({ page }) => {
    await callTool(page, "Lazy Auth Demo", "show_auth_button");
    const app = appFrame(page);
    await expect(app.getByText("Public App")).toBeVisible({ timeout: 30000 });
    await expect(app.locator("#auth-btn")).toHaveText("Auth me");
    await expect(app.locator("#revoke-btn")).toHaveText("Revoke token");
    await expect(app.locator("#fullscreen-btn")).toBeVisible();
    // The session line comes from the server's public whoami tool: no token reaches the View.
    await expect(app.locator("#session")).toContainText("Not signed in", { timeout: 30000 });
    await expect(app.locator("#session")).toContainText("get_secret");
    await expect(app.locator("#signin-btn")).toBeVisible();
  });
});
