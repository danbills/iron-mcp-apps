import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test.describe("Sheet Music Server", () => {
  test("renders the default tune as SVG with play controls", async ({ page }) => {
    await callTool(page, "Sheet Music Server");
    const app = appFrame(page);
    await expect(app.locator("#sheet-music svg")).toBeVisible({ timeout: 30000 });
    await expect(app.locator("#status")).toHaveText("Ready to play!");
    await expect(app.locator(".abcjs-midi-start")).toBeVisible();
  });

  test("notation abcjs cannot parse is the tool's error, with abcjs's own warnings", async ({ page }) => {
    await callTool(page, "Sheet Music Server", "play-sheet-music", { abcNotation: "X:1\nK:C\nC D E F |]\n%%%\nZZZ!!!@@@" });
    await expect(page.getByText("Invalid ABC notation").first()).toBeVisible({ timeout: 30000 });
  });
});
