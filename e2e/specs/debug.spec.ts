import { test, expect } from "@playwright/test";
import { callAppTool, harnessFrame, hostEvents, openHarness } from "./helpers";

test.describe("Debug MCP App Server", () => {
  test.beforeEach(async ({ page }) => {
    await openHarness(page, "debug-server", "debug-tool", { contentType: "mixed" });
    await expect(harnessFrame(page).locator("#callback-table-body tr")).toHaveCount(9, { timeout: 30000 });
  });

  test("the View's own tool handler: an empty list, and every call answered by hand", async ({ page }) => {
    const { tools } = await page.evaluate(() => (window as any).harness.listAppTools());
    expect(tools).toEqual([]);
    const r = await callAppTool(page, "anything", { x: 1 });
    expect(r.content[0].text).toBe("App handled tool call");
    await expect(harnessFrame(page).locator("#callback-table-body")).toContainText("oncalltool");
  });

  test("every request a View can make reaches the host", async ({ page }) => {
    const app = harnessFrame(page);
    await app.locator("#send-message-text-btn").click();
    await app.locator("#send-message-image-btn").click();
    await app.locator("#log-warning-btn").click();
    await app.locator("#update-context-structured-btn").click();
    await app.locator("#update-context-image-btn").click();
    await app.locator("#display-fullscreen-btn").click();
    await app.locator("#open-link-btn").click();
    await app.locator("#resize-400x300-btn").click();
    await expect.poll(async () => (await hostEvents(page, "message")).length).toBe(2);
    expect((await hostEvents(page, "message"))[1].content[0].type).toBe("image");
    await expect.poll(async () => (await hostEvents(page, "log")).map((l) => l.level)).toContain("warning");
    const contexts = await hostEvents(page, "update-model-context");
    expect(contexts.some((c) => c.structuredContent?.debugState)).toBe(true);
    expect(contexts.some((c) => c.content?.[0]?.type === "image")).toBe(true);
    expect((await hostEvents(page, "request-display-mode"))[0]).toEqual({ mode: "fullscreen" });
    expect((await hostEvents(page, "open-link"))[0]).toEqual({ url: "https://modelcontextprotocol.io/" });
    expect(await hostEvents(page, "size")).toContainEqual({ width: 400, height: 300 });
  });

  test("debug-tool's result reached the View: mixed content, structured content, _meta", async ({ page }) => {
    const [result] = await hostEvents(page, "tool-result");
    expect(result.content.map((c: any) => c.type)).toEqual(["text", "image", "audio"]);
    expect(result.structuredContent.config).toMatchObject({ contentType: "mixed", multipleBlocks: true });
    expect(result._meta.debugInfo.serverVersion).toBe("1.0.0");
    await expect(harnessFrame(page).locator("#event-log")).toContainText("ontoolresult");
  });
});
