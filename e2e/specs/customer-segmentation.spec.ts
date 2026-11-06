import { test, expect } from "@playwright/test";
import { appFrame, callTool } from "./helpers";

// Upstream's suite masks the chart; check the legend, the axes and the detail panel.
test("Customer Segmentation: 250 customers in four segments, legend toggles, detail on hover", async ({ page }) => {
  await callTool(page, "Customer Segmentation Server");
  const app = appFrame(page);
  await expect(app.locator(".legend-item")).toHaveCount(4, { timeout: 30000 });
  const counts = await app.locator(".legend-count").allTextContents();
  expect(counts.map((c) => Number(c.replace(/[()]/g, ""))).reduce((a, b) => a + b, 0)).toBe(250);
  await app.locator(".legend-item").first().click();
  await expect(app.locator(".legend-item.hidden")).toHaveCount(1);
  await app.locator("#size-metric").selectOption("nps");
  // Hover the chart until a point is under the pointer.
  const box = (await app.locator("#scatter-chart").boundingBox())!;
  let found = false;
  for (let x = 0.1; x < 0.95 && !found; x += 0.05) {
    for (let y = 0.1; y < 0.95 && !found; y += 0.05) {
      await page.mouse.move(box.x + box.width * x, box.y + box.height * y);
      found = (await app.locator(".detail-name").count()) > 0;
    }
  }
  expect(found).toBe(true);
  await expect(app.locator(".detail-panel")).toContainText("engagement");
});
