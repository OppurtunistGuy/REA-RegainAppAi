// @vitest-environment jsdom
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InterstitialController, effectivePolicy, type InterstitialDeps } from "../src/interstitial/controller.js";
import { parseInterstitialHref, safeReturnUrl, surfaceName } from "../src/interstitial/params.js";
import type { Plan, PublishedStatus, UnlockGrant } from "../src/types.js";
import { UnlockFlow, reasonCounts } from "../src/unlock.js";

const here = dirname(fileURLToPath(import.meta.url));
const html = readFileSync(join(here, "../static/interstitial.html"), "utf8");
const bodyHtml = /<body>([\s\S]*)<\/body>/.exec(html)![1]!.replace(/<script[\s\S]*?<\/script>/g, "");

const T0 = Date.parse("2026-11-24T10:45:00Z");
const plan: Plan = {
  version: 3,
  distractions: [{ id: "youtube.shorts", mode: "hide_section" }],
  schedules: [{ id: "coaching", label: "Coaching", days: [2], start: "16:00", end: "19:00" }],
  unlockPolicy: { waitSeconds: 30, requireReason: true, notifyKeyHolder: true, approvalMinutes: 15 },
  siteBlocks: ["instagram.com"],
};
const status = (over: Partial<PublishedStatus> = {}): PublishedStatus => ({
  computedAt: T0,
  active: true,
  scheduleId: "coaching",
  label: "Coaching",
  strict: true,
  endsAt: T0 + 3_600_000,
  blockedSurfaces: ["youtube.shorts"],
  blockedDomains: ["instagram.com"],
  next: null,
  signedIn: true,
  lastSyncAt: null,
  lastSyncError: null,
  ...over,
});

const $ = <T extends HTMLElement = HTMLElement>(id: string): T => document.getElementById(id) as T;
const click = (id: string): void => $(id).click();
const type = (text: string): void => {
  const input = $<HTMLInputElement>("reason");
  input.value = text;
  input.dispatchEvent(new Event("input"));
};

function setup(over: Partial<InterstitialDeps> = {}) {
  const calls = { closed: 0, navigated: [] as string[], grants: [] as UnlockGrant[] };
  const deps: InterstitialDeps = {
    params: parseInterstitialHref(
      "chrome-extension://id/interstitial.html?surface=site%3Ainstagram.com&site=instagram.com&url=https://www.instagram.com/p/abc/?a=1&b=2#x",
    ),
    status: status(),
    plan,
    grants: [],
    closeTab: () => calls.closed++,
    navigate: (u) => calls.navigated.push(u),
    saveGrant: async (g) => {
      calls.grants.push(g);
    },
    ...over,
  };
  const c = new InterstitialController(document, deps);
  c.start();
  return { c, calls };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
  document.body.innerHTML = bodyHtml;
});
afterEach(() => vi.useRealTimers());

describe("params", () => {
  it("parses raw DNR urls with & and #", () => {
    const p = parseInterstitialHref(
      "chrome-extension://id/interstitial.html?surface=site%3Ainstagram.com&site=instagram.com&url=https://www.instagram.com/p/abc/?a=1&b=2#x",
    );
    expect(p).toEqual({ surface: "site:instagram.com", site: "instagram.com", url: "https://www.instagram.com/p/abc/?a=1&b=2#x" });
  });
  it("parses encoded content-script urls", () => {
    const p = parseInterstitialHref(
      `chrome-extension://id/interstitial.html?surface=youtube.shorts&site=youtube.com&url=${encodeURIComponent("https://www.youtube.com/shorts/x?y=1")}`,
    );
    expect(p.url).toBe("https://www.youtube.com/shorts/x?y=1");
    expect(p.surface).toBe("youtube.shorts");
  });
  it("defaults the surface to site:<site>", () => {
    expect(parseInterstitialHref("chrome-extension://id/interstitial.html?site=x.com").surface).toBe("site:x.com");
  });
  it("only returns to the blocked site over http(s)", () => {
    expect(safeReturnUrl("https://m.instagram.com/x", "instagram.com")).toBe("https://m.instagram.com/x");
    expect(safeReturnUrl("https://evil.com/instagram.com", "instagram.com")).toBe("https://instagram.com/");
    expect(safeReturnUrl("javascript:alert(1)", "instagram.com")).toBe("https://instagram.com/");
    expect(safeReturnUrl(null, "")).toBeNull();
  });
  it("names surfaces", () => {
    expect(surfaceName("youtube.shorts", "youtube.com")).toBe("YouTube Shorts");
    expect(surfaceName("site:reddit.com", "reddit.com")).toBe("reddit.com");
  });
});

describe("UnlockFlow port", () => {
  const policy = { waitSeconds: 30, requireReason: true, notifyKeyHolder: true, approvalMinutes: 15 };
  it("waits, then needs a reason, then is ready or needs the key holder", () => {
    const f = new UnlockFlow(policy, false);
    expect(f.step(0, 29_001, "a good long reason")).toBe("WAITING");
    expect(f.secondsLeft(0, 29_001)).toBe(1);
    expect(f.step(0, 30_000, "short")).toBe("NEEDS_REASON");
    expect(f.step(0, 30_000, "a b c d e f g")).toBe("NEEDS_REASON"); // 7 non-space chars
    expect(f.step(0, 30_000, "a b c d e f g h")).toBe("READY");
    expect(new UnlockFlow(policy, true).step(0, 30_000, "lecture link")).toBe("NEEDS_KEYHOLDER");
    expect(new UnlockFlow({ ...policy, requireReason: false }, false).step(0, 30_000, "")).toBe("READY");
  });
  it("self grants for approvalMinutes", () => {
    const g = new UnlockFlow(policy, false).selfGrant("youtube.shorts", 1000);
    expect(g).toMatchObject({ surfaceId: "youtube.shorts", from: 1000, until: 1000 + 15 * 60_000, approvedBy: null });
    expect(reasonCounts("        ")).toBe(false);
  });
  it("non-strict blocks drop the wait and reason", () => {
    expect(effectivePolicy(plan, false)).toMatchObject({ waitSeconds: 0, requireReason: false });
    expect(effectivePolicy(plan, true)).toMatchObject({ waitSeconds: 30, requireReason: true });
    expect(effectivePolicy(null, true).approvalMinutes).toBe(15);
  });
});

describe("interstitial flow", () => {
  it("5-second breath: Close works at once, other exits unlock after", () => {
    const { c, calls } = setup();
    expect($("blockLine").textContent).toBe("You opened instagram.com during Coaching.");
    expect($("breathDot").textContent).toBe("5");
    expect($<HTMLButtonElement>("swapBtn").disabled).toBe(true);
    expect($<HTMLButtonElement>("unlockBtn").disabled).toBe(true);
    expect($("unlockBtn").textContent).toBe("Unlock for 15 min");
    vi.advanceTimersByTime(4000);
    expect($("breathDot").textContent).toBe("1");
    expect($<HTMLButtonElement>("swapBtn").disabled).toBe(true);
    vi.advanceTimersByTime(1000);
    expect(c.view).toBe("choices");
    expect($<HTMLButtonElement>("swapBtn").disabled).toBe(false);
    expect($<HTMLButtonElement>("unlockBtn").disabled).toBe(false);
    click("closeBtn");
    expect(calls.closed).toBe(1);
  });

  it("Close is available during the breath", () => {
    const { calls } = setup();
    vi.advanceTimersByTime(1000);
    click("closeBtn");
    expect(calls.closed).toBe(1);
  });

  it("60-second swap shows the placeholder card and counts down", () => {
    const { c, calls } = setup();
    vi.advanceTimersByTime(5000);
    click("swapBtn");
    expect(c.view).toBe("swap");
    expect($("swapPanel").hidden).toBe(false);
    expect($("choicesPanel").hidden).toBe(true);
    expect($("swapPanel").textContent).toContain("phone app");
    expect($("swapTime").textContent).toBe("1:00");
    vi.advanceTimersByTime(15_000);
    expect($("swapTime").textContent).toBe("0:45");
    vi.advanceTimersByTime(60_000);
    expect($("swapTime").textContent).toBe("0:00");
    click("swapDone");
    expect(calls.closed).toBe(1);
  });

  it("unlock needs the wait and an 8+ character reason, then grants and returns", async () => {
    const { c, calls } = setup();
    vi.advanceTimersByTime(5000);
    click("unlockBtn");
    expect(c.view).toBe("unlock");
    const confirm = $<HTMLButtonElement>("unlockConfirm");
    type("need the lecture notes");
    expect(confirm.disabled).toBe(true);
    expect($("unlockWait").textContent).toContain("Wait 30s");
    vi.advanceTimersByTime(29_000);
    expect(confirm.disabled).toBe(true);
    expect(await c.confirmUnlock()).toBe(false);
    vi.advanceTimersByTime(1000);
    expect(confirm.disabled).toBe(false);
    type("short");
    expect(confirm.disabled).toBe(true);
    expect(c.unlockStep()).toBe("NEEDS_REASON");
    type("lecture link from my teacher");
    expect(confirm.disabled).toBe(false);
    expect(await c.confirmUnlock()).toBe(true);
    expect(calls.grants).toHaveLength(1);
    expect(calls.grants[0]).toMatchObject({ surfaceId: "site:instagram.com", until: Date.now() + 15 * 60_000 });
    expect(calls.navigated).toEqual(["https://www.instagram.com/p/abc/?a=1&b=2#x"]);
  });

  it("with a key holder the unlock still works locally and says so", async () => {
    const { c, calls } = setup({ plan: { ...plan, keyHolder: { id: "kh", name: "Asha", relation: "friend" } } });
    vi.advanceTimersByTime(5000);
    click("unlockBtn");
    expect($("keyholderNote").hidden).toBe(false);
    expect($("keyholderNote").textContent).toContain("Asha");
    vi.advanceTimersByTime(30_000);
    type("lecture link from my teacher");
    expect(c.unlockStep()).toBe("NEEDS_KEYHOLDER");
    expect(await c.confirmUnlock()).toBe(true);
    expect(calls.grants).toHaveLength(1);
  });

  it("non-strict block: unlock is ready right away", () => {
    const { c } = setup({ status: status({ strict: false }) });
    vi.advanceTimersByTime(5000);
    click("unlockBtn");
    expect(c.unlockStep()).toBe("READY");
    expect($<HTMLButtonElement>("unlockConfirm").disabled).toBe(false);
  });

  it("Never mind returns to the choices", () => {
    const { c } = setup();
    vi.advanceTimersByTime(5000);
    click("unlockBtn");
    click("unlockCancel");
    expect(c.view).toBe("choices");
    expect($("choicesPanel").hidden).toBe(false);
  });

  it("shows the idle panel when the block is over or already unlocked", () => {
    const over = setup({ status: status({ active: false }) });
    expect(over.c.view).toBe("idle");
    click("continueBtn");
    expect(over.calls.navigated).toEqual(["https://www.instagram.com/p/abc/?a=1&b=2#x"]);

    document.body.innerHTML = bodyHtml;
    const granted = setup({
      grants: [{ id: "g", surfaceId: "site:instagram.com", from: T0 - 1, until: T0 + 60_000, approvedBy: null }],
    });
    expect(granted.c.view).toBe("idle");

    document.body.innerHTML = bodyHtml;
    const unlisted = setup({ status: status({ blockedDomains: [] }) });
    expect(unlisted.c.view).toBe("idle");
  });
});
