import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

test("SaaS Scenario Modeler: compare to a template, load it, reset", async ({ page }) => {
  await callTool(page, "SaaS Scenario Modeler");
  const app = appFrame(page);
  await expect(app.locator(".template-select option")).toHaveCount(6, { timeout: 30000 });
  await app.locator(".template-select").selectOption("vc-rocketship");
  await expect(app.locator(".metrics-column.template h3")).toHaveText("vs. VC Rocketship");
  await expect(app.locator(".legend-template")).toHaveText("(dashed = VC Rocketship)");
  await expect(app.locator(".summary-item")).toHaveCount(3);
  await app.locator("#load").click();
  await expect(app.locator(".slider-value").first()).toHaveText("$100K");
  await expect(app.locator(".metrics-column.template")).toHaveCount(0);
  await app.locator(".slider-input").nth(1).fill("10");
  await expect(app.locator(".slider-value").nth(1)).toHaveText("10%");
  await app.locator("#reset").click();
  await expect(app.locator(".slider-value").first()).toHaveText("$50K");
});
