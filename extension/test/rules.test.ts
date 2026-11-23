import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { RulesEngine, hasGrant } from "../src/rules.js";
import type { Plan, UnlockGrant } from "../src/types.js";
import { atLocal, isoDayOfWeek, localDateOf, offsetAt } from "../src/tz.js";

const here = dirname(fileURLToPath(import.meta.url));
const vectors = JSON.parse(
  readFileSync(join(here, "../../shared/schema/test-vectors.json"), "utf8"),
) as {
  zone: string;
  plan: Plan;
  cases: Array<{
    name: string;
    now: string;
    state: "active" | "idle";
    scheduleId?: string;
    endsAt?: string;
    nextScheduleId?: string;
    nextStartsAt?: string;
  }>;
};

const t = (iso: string): number => Date.parse(iso);

describe("shared test vectors", () => {
  const engine = new RulesEngine(vectors.zone);
  it("has cases", () => expect(vectors.cases.length).toBeGreaterThan(0));
  for (const c of vectors.cases) {
    it(c.name, () => {
      const now = t(c.now);
      const st = engine.state(vectors.plan, now);
      expect(st.kind).toBe(c.state);
      if (c.state === "active") {
        if (st.kind !== "active") throw new Error("expected active");
        expect(st.scheduleId).toBe(c.scheduleId);
        if (c.endsAt) expect(new Date(st.endsAt).toISOString()).toBe(new Date(c.endsAt).toISOString());
        expect(st.blocked.map((s) => s.id)).toEqual(vectors.plan.distractions.map((s) => s.id));
      }
      if (c.nextScheduleId) {
        const next = engine.nextBlock(vectors.plan, now);
        expect(next?.scheduleId).toBe(c.nextScheduleId);
        expect(new Date(next!.startsAt).toISOString()).toBe(new Date(c.nextStartsAt!).toISOString());
        expect(next!.startsAt).toBeGreaterThan(now);
      }
    });
  }
});

const base = (schedules: Plan["schedules"]): Plan => ({
  version: 1,
  distractions: [
    { id: "youtube.shorts", mode: "hide_section" },
    { id: "site:reddit.com", mode: "block_app" },
  ],
  schedules,
});

describe("RulesEngine extra semantics", () => {
  const utc = new RulesEngine("UTC");
  // 2026-11-23 is a Monday.
  it("overlap: strict beats non-strict", () => {
    const plan = base([
      { id: "a", label: "A", days: [1], start: "10:00", end: "14:00", strict: false },
      { id: "b", label: "B", days: [1], start: "11:00", end: "12:00", strict: true },
    ]);
    const st = utc.state(plan, t("2026-11-23T11:30:00Z"));
    expect(st.kind === "active" && st.scheduleId).toBe("b");
  });
  it("overlap: later end wins among equals, then lowest id", () => {
    const plan = base([
      { id: "z", label: "Z", days: [1], start: "10:00", end: "12:00" },
      { id: "y", label: "Y", days: [1], start: "11:00", end: "13:00" },
      { id: "x", label: "X", days: [1], start: "09:00", end: "13:00" },
    ]);
    const st = utc.state(plan, t("2026-11-23T11:30:00Z"));
    expect(st.kind === "active" && st.scheduleId).toBe("x");
    expect(st.kind === "active" && st.strict).toBe(true); // strict defaults to true
  });
  it("midnight crossing belongs to the start day only", () => {
    const plan = base([{ id: "n", label: "N", days: [1], start: "23:00", end: "01:00" }]);
    expect(utc.state(plan, t("2026-11-24T00:30:00Z")).kind).toBe("active"); // Tue 00:30, from Monday
    expect(utc.state(plan, t("2026-11-24T23:30:00Z")).kind).toBe("idle"); // Tue night not scheduled
    expect(utc.state(plan, t("2026-11-23T00:30:00Z")).kind).toBe("idle"); // Mon 00:30 needs Sunday
  });
  it("nextBlock is strictly after now and returns null with no schedules", () => {
    const plan = base([{ id: "m", label: "M", days: [1], start: "10:00", end: "11:00" }]);
    const next = utc.nextBlock(plan, t("2026-11-23T10:00:00Z"));
    expect(new Date(next!.startsAt).toISOString()).toBe("2026-11-30T10:00:00.000Z");
    expect(utc.nextBlock(base([]), t("2026-11-23T10:00:00Z"))).toBeNull();
  });
  it("isBlocked honours grants (surface and global)", () => {
    const plan = base([{ id: "m", label: "M", days: [1], start: "10:00", end: "11:00" }]);
    const now = t("2026-11-23T10:15:00Z");
    const g = (surfaceId: string | null): UnlockGrant => ({
      id: "g",
      surfaceId,
      from: now - 1000,
      until: now + 60_000,
      approvedBy: null,
    });
    expect(utc.isBlocked(plan, [], "youtube.shorts", now)).toBe(true);
    expect(utc.isBlocked(plan, [], "instagram.reels", now)).toBe(false); // not in plan
    expect(utc.isBlocked(plan, [g("youtube.shorts")], "youtube.shorts", now)).toBe(false);
    expect(utc.isBlocked(plan, [g("site:reddit.com")], "youtube.shorts", now)).toBe(true);
    expect(utc.isBlocked(plan, [g(null)], "site:reddit.com", now)).toBe(false);
    expect(utc.isBlocked(plan, [], "youtube.shorts", t("2026-11-23T11:00:00Z"))).toBe(false);
    // grant end is exclusive
    expect(hasGrant([g(null)], "x", now + 60_000)).toBe(false);
  });
  it("blocksOn lists the day's blocks in start order", () => {
    const engine = new RulesEngine(vectors.zone);
    const list = engine.blocksOn(vectors.plan, { year: 2026, month: 11, day: 28 }); // Saturday
    expect(list.map((b) => b.scheduleId)).toEqual(["night", "late"]);
    expect(new Date(list[1]!.endsAt).toISOString()).toBe("2026-11-28T19:30:00.000Z");
  });
});

describe("tz helper", () => {
  it("knows ISO weekdays", () => {
    expect(isoDayOfWeek({ year: 2026, month: 11, day: 23 })).toBe(1);
    expect(isoDayOfWeek({ year: 2026, month: 11, day: 29 })).toBe(7);
  });
  it("converts local dates in a fixed-offset zone", () => {
    expect(new Date(atLocal({ year: 2026, month: 11, day: 24 }, 16 * 60, "Asia/Kolkata")).toISOString()).toBe(
      "2026-11-24T10:30:00.000Z",
    );
    expect(localDateOf(t("2026-11-28T19:00:00Z"), "Asia/Kolkata")).toEqual({ year: 2026, month: 11, day: 29 });
    expect(offsetAt(t("2026-06-01T00:00:00Z"), "Asia/Kolkata")).toBe(330 * 60_000);
  });
  it("handles DST like java.time (gap pushes forward, overlap takes earlier)", () => {
    const ny = "America/New_York";
    // 2026-03-08 02:30 does not exist in New York -> 03:30 EDT = 07:30Z
    expect(new Date(atLocal({ year: 2026, month: 3, day: 8 }, 150, ny)).toISOString()).toBe("2026-03-08T07:30:00.000Z");
    // 2026-11-01 01:30 happens twice -> earlier (EDT) = 05:30Z
    expect(new Date(atLocal({ year: 2026, month: 11, day: 1 }, 90, ny)).toISOString()).toBe("2026-11-01T05:30:00.000Z");
    // ordinary times on both sides
    expect(new Date(atLocal({ year: 2026, month: 7, day: 1 }, 600, ny)).toISOString()).toBe("2026-07-01T14:00:00.000Z");
    expect(new Date(atLocal({ year: 2026, month: 12, day: 1 }, 600, ny)).toISOString()).toBe("2026-12-01T15:00:00.000Z");
  });
});
