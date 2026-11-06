import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

// Upstream's suite skips qr-server (its Python server needs the old mcp 1.x API).
test.describe("QR Code Server", () => {
  test("generate_qr: the View shows the PNG from the image block", async ({ page }) => {
    await callTool(page, "QR Code Server");
    const img = appFrame(page).locator("#qr img");
    await expect(img).toBeVisible({ timeout: 30000 });
    expect(await img.getAttribute("src")).toMatch(/^data:image\/png;base64,iVBORw0KGgo/);
    expect(await img.evaluate((el) => (el as HTMLImageElement).naturalWidth)).toBeGreaterThan(200);
  });

  test("generate_qr with colours and a level: still an image, and a bad colour is the tool's error", async ({ page }) => {
    await callTool(page, "QR Code Server", "generate_qr", { text: "hello", fill_color: "#FF0000", error_correction: "H" });
    await expect(appFrame(page).locator("#qr img")).toBeVisible({ timeout: 30000 });
    await callTool(page, "QR Code Server", "generate_qr", { fill_color: "blurple" });
    await expect(page.getByText("unknown color specifier: blurple").first()).toBeVisible({ timeout: 30000 });
  });
});
