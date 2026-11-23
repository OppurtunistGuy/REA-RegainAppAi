import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  SITE_RULE_BASE,
  SURFACE_RULE_BASE,
  SURFACE_URL_REGEX,
  buildDnrRules,
  computeStatus,
  isPlan,
  nextTransition,
  normalizeDomain,
  originsFor,
  planDomains,
  pruneGrants,
} from "../src/policy.js";
import type { Plan, UnlockGrant } from "../src/types.js";

const here = dirname(fileURLToPath(import.meta.url));
const vectors = JSON.parse(readFileSync(join(here, "../../shared/schema/test-vectors.json"), "utf8")) as {
  zone: string;
  plan: Plan;
};
const plan: Plan = {
  ...vectors.plan,
  distractions: [...vectors.plan.distractions, { id: "site:Reddit.com", mode: "block_app" }],
  siteBlocks: ["instagram.com", "www.x.com"],
};
const meta = { signedIn: true, lastSyncAt: null, lastSyncError: null };
const inside = Date.parse("2026-11-24T10:45:00Z"); // coaching
const outside = Date.parse("2026-11-24T10:29:00Z");

describe("domains", () => {
  it("normalizes", () => {
    expect(normalizeDomain("https://WWW.Example.com/path?q")).toBe("example.com");
    expect(normalizeDomain("example.com:8080")).toBe("example.com");
    expect(normalizeDomain("localhost")).toBeNull();
    expect(normalizeDomain("not a domain")).toBeNull();
  });
  it("merges siteBlocks and site:* surfaces", () => {
    expect(planDomains(plan)).toEqual(["instagram.com", "reddit.com", "x.com"]);
  });
  it("origins cover subdomains", () => {
    expect(originsFor("x.com")).toEqual(["*://x.com/*", "*://*.x.com/*"]);
  });
});

describe("computeStatus", () => {
  it("idle outside blocks, with next block", () => {
    const s = computeStatus(plan, [], outside, vectors.zone, meta);
    expect(s.active).toBe(false);
    expect(s.blockedDomains).toEqual([]);
    expect(s.blockedSurfaces).toEqual([]);
    expect(s.next?.scheduleId).toBe("coaching");
  });
  it("active inside blocks", () => {
    const s = computeStatus(plan, [], inside, vectors.zone, meta);
    expect(s).toMatchObject({ active: true, scheduleId: "coaching", label: "Coaching", strict: true });
    expect(s.blockedSurfaces).toContain("youtube.shorts");
    expect(s.blockedDomains).toEqual(["instagram.com", "reddit.com", "x.com"]);
  });
  it("applies grants per surface and per site", () => {
    const g = (surfaceId: string | null, until = inside + 60_000): UnlockGrant => ({
      id: surfaceId ?? "all",
      surfaceId,
      from: inside - 1,
      until,
      approvedBy: null,
    });
    const s = computeStatus(plan, [g("youtube.shorts"), g("site:x.com")], inside, vectors.zone, meta);
    expect(s.blockedSurfaces).not.toContain("youtube.shorts");
    expect(s.blockedSurfaces).toContain("instagram.reels");
    expect(s.blockedDomains).toEqual(["instagram.com", "reddit.com"]);
    const all = computeStatus(plan, [g(null)], inside, vectors.zone, meta);
    expect(all.blockedSurfaces).toEqual([]);
    expect(all.blockedDomains).toEqual([]);
    expect(all.active).toBe(true);
  });
  it("no plan -> idle", () => {
    expect(computeStatus(null, [], inside, "UTC", meta)).toMatchObject({ active: false, next: null });
  });
  it("next transition is the earliest of block end, next start and grant expiry", () => {
    const s = computeStatus(plan, [], inside, vectors.zone, meta);
    expect(nextTransition(s, [], inside)).toBe(s.endsAt);
    const g: UnlockGrant = { id: "g", surfaceId: null, from: inside, until: inside + 5 * 60_000, approvedBy: null };
    expect(nextTransition(s, [g], inside)).toBe(inside + 5 * 60_000);
    expect(pruneGrants([g], inside + 5 * 60_000)).toEqual([]);
  });
});

describe("buildDnrRules", () => {
  const ext = "abcdefghijklmnopabcdefghijklmnop";
  it("no rules when nothing is blocked", () => {
    expect(buildDnrRules({ extensionId: ext, blockedDomains: [], blockedSurfaces: [], canRedirect: () => true })).toEqual([]);
  });
  it("redirects main_frame for sites with permission, blocks without", () => {
    const rules = buildDnrRules({
      extensionId: ext,
      blockedDomains: ["instagram.com", "reddit.com"],
      blockedSurfaces: ["youtube.shorts", "instagram.reels", "app:com.pubg.imobile"],
      canRedirect: (d) => d === "instagram.com",
    });
    expect(rules.map((r) => r.id)).toEqual([SURFACE_RULE_BASE, SURFACE_RULE_BASE + 1, SITE_RULE_BASE, SITE_RULE_BASE + 1]);
    const ig = rules.find((r) => r.id === SITE_RULE_BASE)!;
    expect(ig.action.type).toBe("redirect");
    expect(ig.action.redirect?.regexSubstitution).toBe(
      `chrome-extension://${ext}/interstitial.html?surface=site%3Ainstagram.com&site=instagram.com&url=\\0`,
    );
    expect(ig.condition.requestDomains).toEqual(["instagram.com"]);
    expect(ig.condition.resourceTypes).toEqual(["main_frame"]);
    expect(ig.priority).toBe(2);
    const reddit = rules.find((r) => r.id === SITE_RULE_BASE + 1)!;
    expect(reddit.action.type).toBe("block");
    expect(reddit.condition.regexFilter).toBeUndefined();
    const shorts = rules.find((r) => r.id === SURFACE_RULE_BASE)!;
    expect(shorts.condition.regexFilter).toBe(SURFACE_URL_REGEX["youtube.shorts"].regex);
    expect(shorts.action.redirect?.regexSubstitution).toContain("surface=youtube.shorts&site=youtube.com&url=\\0");
    for (const r of rules) expect(r.condition.resourceTypes).toEqual(["main_frame"]);
  });
  it("surface regexes match only the full-screen feeds", () => {
    const yt = new RegExp(SURFACE_URL_REGEX["youtube.shorts"].regex);
    expect(yt.test("https://www.youtube.com/shorts/abc123?feature=share")).toBe(true);
    expect(yt.test("https://m.youtube.com/shorts/abc")).toBe(true);
    expect(yt.test("https://www.youtube.com/watch?v=abc")).toBe(false);
    expect(yt.test("https://www.youtube.com/@chan/shorts")).toBe(false);
    expect(yt.test("https://notyoutube.com/shorts/x")).toBe(false);
    const ig = new RegExp(SURFACE_URL_REGEX["instagram.reels"].regex);
    expect(ig.test("https://www.instagram.com/reels/")).toBe(true);
    expect(ig.test("https://www.instagram.com/reel/C0abc/")).toBe(true);
    expect(ig.test("https://www.instagram.com/direct/inbox/")).toBe(false);
  });
  it("rule ids are unique and stable", () => {
    const input = {
      extensionId: ext,
      blockedDomains: ["a.com", "b.com", "c.com"],
      blockedSurfaces: ["youtube.shorts"],
      canRedirect: () => true,
    };
    const a = buildDnrRules(input);
    expect(new Set(a.map((r) => r.id)).size).toBe(a.length);
    expect(buildDnrRules(input)).toEqual(a);
  });
});

describe("isPlan", () => {
  it("accepts the vector plan and rejects junk", () => {
    expect(isPlan(vectors.plan)).toBe(true);
    expect(isPlan(null)).toBe(false);
    expect(isPlan({ version: 1, distractions: [], schedules: [{ id: "a", label: "A", days: [8], start: "10:00", end: "11:00" }] })).toBe(false);
    expect(isPlan({ version: 1, distractions: [], schedules: [{ id: "a", label: "A", days: [1], start: "25:00", end: "11:00" }] })).toBe(false);
    expect(isPlan({ version: 1, distractions: [], schedules: [], siteBlocks: [3] })).toBe(false);
  });
});
