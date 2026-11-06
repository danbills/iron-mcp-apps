import { test, expect } from "@playwright/test";
import { harnessFrame, hostEvents, openHarness } from "./helpers";

// basic-host does not offer downloadFile; the test host does, so the buttons appear.
test("Integration Test Server: downloads, embedded and by link", async ({ page }) => {
  await openHarness(page, "integration-server", "get-time");
  const app = harnessFrame(page);
  await expect(app.locator(".serverTime")).not.toHaveText("Loading...", { timeout: 30000 });
  await app.getByRole("button", { name: "Embedded", exact: true }).click();
  await app.getByRole("button", { name: "Link", exact: true }).click();
  await expect.poll(async () => (await hostEvents(page, "download-file")).length).toBe(2);
  const [embedded, link] = await hostEvents(page, "download-file");
  expect(embedded.contents[0].resource).toMatchObject({ uri: "file:///export.json", mimeType: "application/json" });
  expect(JSON.parse(embedded.contents[0].resource.text)).toHaveProperty("exported");
  expect(link.contents[0]).toMatchObject({ type: "resource_link", uri: "resource:///sample-report.txt", name: "sample-report.txt" });
});
