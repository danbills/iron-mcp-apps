import { test, expect } from "@playwright/test";
import { callAppTool, harnessFrame, openHarness } from "./helpers";

const text = (r: any) => r.content.map((c: any) => c.text).join("\n");

test.describe("Budget Allocator: the View's tools", () => {
  test.beforeEach(async ({ page }) => {
    await openHarness(page, "budget-allocator-server", "get-budget-data");
    await expect(harnessFrame(page).locator(".slider-row")).toHaveCount(5, { timeout: 30000 });
  });

  test("tools/list names the five tools, with input schemas", async ({ page }) => {
    const { tools } = await page.evaluate(() => (window as any).harness.listAppTools());
    expect(tools.map((t: any) => t.name)).toEqual([
      "get-allocations", "set-allocation", "set-total-budget", "set-company-stage", "get-benchmark-comparison",
    ]);
    const set = tools.find((t: any) => t.name === "set-allocation");
    expect(set.inputSchema.properties.percent).toMatchObject({ type: "number", minimum: 0, maximum: 100 });
  });

  test("set-allocation moves the slider, the doughnut's data and the status bar", async ({ page }) => {
    const r = await callAppTool(page, "set-allocation", { categoryId: "rd", percent: 30 });
    expect(text(r)).toBe("Set R&D allocation to 30.0% ($30,000)");
    const app = harnessFrame(page);
    await expect(app.locator('.slider-row[data-category-id="rd"] .percent')).toHaveText("30.0%");
    await expect(app.locator('.slider-row[data-category-id="rd"] .slider')).toHaveValue("30");
    await expect(app.locator("#status-bar")).toContainText("Over");
    const all = await callAppTool(page, "get-allocations");
    expect(all.structuredContent.allocations.rd).toEqual({ percent: 30, amount: 30000 });
    expect(JSON.parse(text(all)).totalBudget).toBe(100000);
  });

  test("set-total-budget and set-company-stage drive the selectors; bad input is an isError result", async ({ page }) => {
    expect(text(await callAppTool(page, "set-total-budget", { amount: 250000 }))).toBe("Total budget set to $250,000");
    const app = harnessFrame(page);
    await expect(app.locator("#budget-selector")).toHaveValue("250000");
    expect(text(await callAppTool(page, "set-company-stage", { stage: "Seed" }))).toBe('Company stage set to "Seed"');
    await expect(app.locator("#stage-selector")).toHaveValue("Seed");
    const comparison = await callAppTool(page, "get-benchmark-comparison");
    expect(comparison.structuredContent.stage).toBe("Seed");
    expect(comparison.structuredContent.comparison.engineering).toMatchObject({ current: 35, p50: 47, status: "below p25" });

    expect((await callAppTool(page, "set-company-stage", { stage: "IPO" })).isError).toBe(true);
    expect((await callAppTool(page, "set-allocation", { categoryId: "legal", percent: 5 })).isError).toBe(true);
    expect((await callAppTool(page, "set-allocation", { categoryId: "rd", percent: 150 })).isError).toBe(true);
  });

  test("an unknown tool is a JSON-RPC error", async ({ page }) => {
    const outcome = await page.evaluate(() =>
      (window as any).harness.callAppTool("nope").then(() => "answered", (e: any) => String(e?.code ?? e)),
    );
    expect(outcome).toContain("-32602");
  });
});
