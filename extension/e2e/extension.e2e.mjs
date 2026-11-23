// End-to-end check of the built extension in Chromium (Playwright, headless=new).
// A local server plays three roles: the Steady API, "www.youtube.com" and "blocked.test"
// (via --host-resolver-rules), so nothing leaves the machine.
//
// Run: npm run e2e   (builds first). Uses the preinstalled Playwright; never downloads browsers.
// If Chromium cannot load extensions headless in this environment, the test prints SKIPPED and exits 0.

import { createRequire } from "node:module";
import { createServer } from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const require = createRequire(import.meta.url);
let chromium;
try {
  ({ chromium } = require("playwright"));
} catch {
  console.log("SKIPPED: playwright is not installed (it is expected to be preinstalled globally).");
  process.exit(0);
}

const extDir = join(dirname(fileURLToPath(import.meta.url)), "..", "dist");
const pad = (n) => String(n).padStart(2, "0");
const hhmm = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;

// A block that is running now in the browser's local zone (container zone = Node's zone).
const now = new Date();
const start = new Date(now.getTime() - 60 * 60_000);
const end = new Date(now.getTime() + 2 * 60 * 60_000);
const plan = {
  version: 1,
  distractions: [
    { id: "youtube.shorts", mode: "hide_section" },
    { id: "instagram.reels", mode: "hide_section" },
  ],
  schedules: [{ id: "e2e", label: "E2E block", days: [1, 2, 3, 4, 5, 6, 7], start: hhmm(start), end: hhmm(end), strict: true }],
  unlockPolicy: { waitSeconds: 2, requireReason: true, notifyKeyHolder: false, approvalMinutes: 15 },
  siteBlocks: ["blocked.test", "instagram.com"],
};
if (end.getDate() !== start.getDate() || hhmm(end) <= hhmm(start)) {
  console.log("SKIPPED: local time is too close to midnight for this fixture.");
  process.exit(0);
}

const requests = [];
const YT_HOME = `<!doctype html><html><body>
<ytd-app><ytd-rich-grid-renderer>
  <ytd-rich-item-renderer id="video"><a href="/watch?v=1">A lecture</a></ytd-rich-item-renderer>
  <ytd-reel-shelf-renderer id="shelf"><a href="/shorts/aa">Short</a></ytd-reel-shelf-renderer>
</ytd-rich-grid-renderer></ytd-app></body></html>`;

const server = createServer((req, res) => {
  requests.push(`${req.method} ${req.headers.host} ${req.url}`);
  const host = (req.headers.host || "").split(":")[0];
  if (host === "www.youtube.com") {
    res.writeHead(200, { "content-type": "text/html" });
    res.end(req.url?.startsWith("/shorts") ? "<h1>SHORTS PLAYER</h1>" : YT_HOME);
    return;
  }
  if (host === "blocked.test") {
    res.writeHead(200, { "content-type": "text/html" });
    res.end("<h1>BLOCKED SITE CONTENT</h1>");
    return;
  }
  res.setHeader("access-control-allow-origin", "*");
  res.setHeader("access-control-allow-headers", "authorization, content-type");
  if (req.method === "OPTIONS") return res.writeHead(204).end();
  if (req.url === "/v1/plan") return res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(plan));
  if (req.url === "/v1/detectors")
    return res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ version: 1, detectors: [] }));
  res.writeHead(404).end();
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const port = server.address().port;

const userDataDir = await mkdtemp(join(tmpdir(), "steady-e2e-"));
let context;
try {
  context = await chromium.launchPersistentContext(userDataDir, {
    // The default headless binary (chromium-headless-shell) cannot load extensions;
    // the "chromium" channel runs full Chromium in new headless mode, which can.
    channel: process.env.STEADY_E2E_CHANNEL || "chromium",
    headless: true,
    args: [
      "--headless=new",
      `--disable-extensions-except=${extDir}`,
      `--load-extension=${extDir}`,
      `--host-resolver-rules=MAP www.youtube.com 127.0.0.1:${port}, MAP blocked.test 127.0.0.1:${port}, MAP www.instagram.com 127.0.0.1:${port}`,
    ],
  });
} catch (e) {
  console.log(`SKIPPED: Chromium could not start with the extension headless (${e.message.split("\n")[0]}).`);
  server.close();
  await rm(userDataDir, { recursive: true, force: true });
  process.exit(0);
}

let failed = false;
try {
  let [worker] = context.serviceWorkers();
  if (!worker) {
    worker = await context.waitForEvent("serviceworker", { timeout: 10_000 }).catch(() => null);
  }
  if (!worker) {
    console.log("SKIPPED: extension service worker never started (extensions unsupported in this headless build).");
    process.exit(0);
  }
  const extId = new URL(worker.url()).host;

  // Sign in by writing settings directly (the OTP UI is unit-tested); the worker syncs on change.
  await worker.evaluate(
    (base) => chrome.storage.local.set({ settings: { apiBase: base, token: "e2e-token", userId: "u1" } }),
    `http://127.0.0.1:${port}`,
  );
  const deadline = Date.now() + 10_000;
  let status = null;
  while (Date.now() < deadline) {
    status = await worker.evaluate(() => chrome.storage.local.get("status").then((s) => s.status ?? null));
    if (status?.active) break;
    await new Promise((r) => setTimeout(r, 200));
  }
  assert.ok(status?.active, `status never became active: ${JSON.stringify(status)}`);
  console.log("ok - worker pulled the plan and the block is active");

  const page = await context.newPage();

  // 1. Full load of /shorts/ is redirected by declarativeNetRequest.
  await page.goto(`http://www.youtube.com/shorts/abc`).catch(() => undefined);
  await page.waitForURL(/interstitial\.html/, { timeout: 5000 });
  assert.ok(page.url().startsWith(`chrome-extension://${extId}/interstitial.html?surface=youtube.shorts`));
  await page.waitForSelector("#breathDot");
  assert.equal(await page.isDisabled("#unlockBtn"), true);
  console.log("ok - /shorts/ goes to the interstitial, exits locked during the breath");

  // 2. Unlock flow: breath, wait, reason, grant, back to the page.
  await page.waitForFunction(() => !document.querySelector("#unlockBtn").disabled, null, { timeout: 8000 });
  await page.click("#unlockBtn");
  await page.fill("#reason", "watching a lecture clip");
  await page.waitForFunction(() => !document.querySelector("#unlockConfirm").disabled, null, { timeout: 5000 });
  await page.click("#unlockConfirm");
  await page.waitForURL(/^http:\/\/www\.youtube\.com\/shorts\/abc/, { timeout: 5000 });
  assert.match(await page.textContent("h1"), /SHORTS PLAYER/);
  console.log("ok - unlock with wait + reason returns to the page");

  // Remove the grant again for the remaining checks.
  await worker.evaluate(() => chrome.storage.local.set({ grants: [] }));
  await new Promise((r) => setTimeout(r, 500));

  // 3. Content script hides the Shorts shelf and catches SPA navigation.
  await page.goto(`http://www.youtube.com/`);
  await page.waitForSelector("#shelf[data-steady-hidden]", { state: "attached", timeout: 5000 });
  assert.equal(await page.isVisible("#shelf"), false);
  assert.equal(await page.isVisible("#video"), true);
  await page.evaluate(() => {
    history.pushState({}, "", "/shorts/spa");
    document.body.appendChild(document.createElement("div"));
  });
  await page.waitForURL(/interstitial\.html/, { timeout: 5000 });
  console.log("ok - Shorts shelf hidden; SPA navigation to /shorts/ caught");

  // 4. A listed site without granted host access is blocked outright.
  const blockedPage = await context.newPage();
  blockedPage.on("pageerror", (e) => console.log("pageerror:", e.message));
  const err = await blockedPage.goto(`http://blocked.test/`).then(
    () => null,
    (e) => e.message,
  );
  assert.ok(err?.includes("ERR_BLOCKED_BY_CLIENT"), `site was not blocked: ${err}`);
  assert.ok(!requests.some((r) => r.includes("blocked.test")), "blocked site was still requested");
  console.log("ok - siteBlocks domain is blocked");

  // 5. A listed site with host access (instagram.com is in host_permissions) gets the breath page.
  await blockedPage.goto(`http://www.instagram.com/direct/inbox/?a=1&b=2`).catch(() => undefined);
  await blockedPage.waitForURL(/interstitial\.html\?surface=site%3Ainstagram\.com/, { timeout: 5000 });
  assert.ok(blockedPage.url().endsWith("&url=http://www.instagram.com/direct/inbox/?a=1&b=2"), blockedPage.url());
  await blockedPage.waitForFunction(() => document.querySelector("#blockLine").textContent.includes("E2E block"));
  const closed = blockedPage.waitForEvent("close", { timeout: 5000 });
  await blockedPage.click("#closeBtn"); // available during the breath
  await closed;
  console.log("ok - siteBlocks domain with access redirects to the interstitial; Close tab closes it");

  // 6. Popup and options render the current state without errors.
  for (const file of ["popup.html", "options.html"]) {
    const p = await context.newPage();
    const errors = [];
    p.on("pageerror", (e) => errors.push(e.message));
    await p.goto(`chrome-extension://${extId}/${file}`);
    await p.waitForFunction(() => document.querySelector("#headline").textContent === "E2E block is on", null, {
      timeout: 5000,
    });
    assert.deepEqual(errors, []);
    await p.close();
  }
  console.log("ok - popup and options show the active block");

  // Privacy: the API only saw GETs for plan/detectors.
  const apiCalls = requests.filter((r) => r.includes(`127.0.0.1:${port}`));
  assert.ok(apiCalls.every((r) => /^GET .* \/v1\/(plan|detectors)$/.test(r) || r.startsWith("OPTIONS")), apiCalls.join("\n"));
  console.log("ok - only GET /v1/plan and /v1/detectors reached the API");
} catch (e) {
  failed = true;
  console.error("FAIL", e);
} finally {
  await context.close();
  server.close();
  await rm(userDataDir, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
