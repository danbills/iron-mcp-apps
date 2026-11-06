import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

// Upstream's suite masks the grid; check what is in it.
test("Cohort Heatmap: a triangle of cells, highlight on click, a tooltip on hover, refetch on metric", async ({ page }) => {
  await callTool(page, "Cohort Heatmap Server");
  const app = appFrame(page);
  await expect(app.locator(".cell")).toHaveCount(78, { timeout: 30000 }); // 12 + 11 + ... + 1
  await expect(app.locator(".cellEmpty")).toHaveCount(144 - 78);
  await expect(app.locator(".label").first()).toContainText(/[A-Z][a-z]{2} \d{4}/);
  await app.locator(".cell").nth(5).click();
  await expect(app.locator(".label.highlighted")).toHaveCount(1);
  await expect(app.locator(".headerPeriod.highlighted")).toHaveText("M5");
  await app.locator(".cell").nth(3).hover();
  await expect(app.locator(".tooltip")).toContainText("Retention:");
  await expect(app.locator(".tooltip")).toContainText("Month 3");
  await app.locator("select").first().selectOption("revenue");
  await expect(app.locator(".label.highlighted")).toHaveCount(0);
  await expect(app.locator(".cell")).toHaveCount(78);
});
