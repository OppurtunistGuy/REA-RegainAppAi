import { describe, expect, it } from "vitest";
import { weeklySummary } from "../src/summary.js";
import { errorCode, harness } from "./helpers.js";

const session = (over: Record<string, unknown> = {}) => ({
  id: "s1",
  scheduleId: "coaching",
  start: "2026-11-23T10:30:00Z",
  end: "2026-11-23T11:00:00Z",
  intercepts: 3,
  unlocks: 1,
  ...over,
});

describe("sessions", () => {
  it("POST stores and returns 204", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const res = await h.req("POST", "/v1/sessions", { token, body: session() });
    expect(res.status).toBe(204);
    expect(await res.text()).toBe("");
    const rows = h.db.prepare("SELECT * FROM sessions").all();
    expect(rows).toHaveLength(1);
  });

  it("is idempotent by id", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    await h.req("POST", "/v1/sessions", { token, body: session() });
    const again = await h.req("POST", "/v1/sessions", { token, body: session({ intercepts: 99 }) });
    expect(again.status).toBe(204);
    const sum = await h.req("GET", "/v1/summary/weekly?weekStart=2026-11-23", { token });
    expect(((await sum.json()) as { intercepts: number; blocks: number })).toMatchObject({ intercepts: 3, blocks: 1 });
  });

  it("same id from different users are separate sessions", async () => {
    const h = harness();
    const a = await h.login("a@b.co");
    const b = await h.login("c@d.co");
    await h.req("POST", "/v1/sessions", { token: a.token, body: session() });
    await h.req("POST", "/v1/sessions", { token: b.token, body: session() });
    expect(h.db.prepare("SELECT * FROM sessions").all()).toHaveLength(2);
  });

  it("accepts scheduleId null and offset timestamps", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const res = await h.req("POST", "/v1/sessions", {
      token,
      body: session({ scheduleId: null, start: "2026-11-23T16:00:00+05:30", end: "2026-11-23T16:25:00.000+05:30" }),
    });
    expect(res.status).toBe(204);
  });

  it("rejects a session carrying screen content (unknown field) with 400", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const res = await h.req("POST", "/v1/sessions", { token, body: session({ screenText: "chat with Rahul…" }) });
    expect(res.status).toBe(400);
    const body = (await res.json()) as { error: { code: string; message: string } };
    expect(body.error.code).toBe("invalid_body");
    expect(body.error.message).toContain("screenText");
    expect(body.error.message).not.toContain("Rahul"); // values are never echoed
    expect(h.db.prepare("SELECT * FROM sessions").all()).toHaveLength(0);
  });

  const invalid: [string, Record<string, unknown>][] = [
    ["negative intercepts", { intercepts: -1 }],
    ["float unlocks", { unlocks: 1.5 }],
    ["string count", { intercepts: "3" }],
    ["non-ISO start", { start: "yesterday" }],
    ["date only", { start: "2026-11-23" }],
    ["end before start", { end: "2026-11-23T10:00:00Z" }],
    ["missing scheduleId", { scheduleId: undefined }],
    ["empty id", { id: "" }],
    ["urls field", { urls: ["https://x"] }],
  ];
  for (const [name, over] of invalid) {
    it(`rejects invalid session: ${name}`, async () => {
      const h = harness();
      const { token } = await h.login("a@b.co");
      const res = await h.req("POST", "/v1/sessions", { token, body: session(over) });
      expect(res.status).toBe(400);
    });
  }
});

describe("weekly summary", () => {
  it("aggregates the week's sessions", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const posts = [
      // Mon 23rd: 30 min + 25 min
      session({ id: "a", start: "2026-11-23T10:30:00Z", end: "2026-11-23T11:00:00Z", intercepts: 3, unlocks: 1 }),
      session({ id: "b", start: "2026-11-23T15:30:00Z", end: "2026-11-23T15:55:00Z", intercepts: 0, unlocks: 0 }),
      // Wed 25th: 40 min 30 s
      session({ id: "c", start: "2026-11-25T09:00:00Z", end: "2026-11-25T09:40:30Z", intercepts: 2, unlocks: 0 }),
      // Sun 29th 23:50 → Mon 00:10 counts for Sunday (start date)
      session({ id: "d", start: "2026-11-29T23:50:00Z", end: "2026-11-30T00:10:00Z", intercepts: 1, unlocks: 2 }),
      // Outside: previous Sunday and next Monday
      session({ id: "e", start: "2026-11-22T23:59:00Z", end: "2026-11-23T00:30:00Z", intercepts: 9, unlocks: 9 }),
      session({ id: "f", start: "2026-11-30T00:00:00Z", end: "2026-11-30T01:00:00Z", intercepts: 9, unlocks: 9 }),
    ];
    for (const p of posts) expect((await h.req("POST", "/v1/sessions", { token, body: p })).status).toBe(204);

    const res = await h.req("GET", "/v1/summary/weekly?weekStart=2026-11-23", { token });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({
      focusMinutes: 30 + 25 + 41 + 20, // 115.5 → rounds the total, not each session
      blocks: 4,
      intercepts: 6,
      unlocks: 3,
      days: ["2026-11-23", "2026-11-25", "2026-11-29"],
    });
  });

  it("rounds the total, not each session", () => {
    const base = Date.parse("2026-11-23T00:00:00Z");
    const s = (startOff: number, lenMs: number) => ({ startMs: base + startOff, endMs: base + startOff + lenMs, intercepts: 0, unlocks: 0 });
    // Three sessions of 20.4 minutes = 61.2 → 61 (per-session rounding would give 60)
    const out = weeklySummary([s(0, 1_224_000), s(3_600_000, 1_224_000), s(7_200_000, 1_224_000)], "2026-11-23");
    expect(out.focusMinutes).toBe(61);
    expect(out.blocks).toBe(3);
  });

  it("empty week returns zeros", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const res = await h.req("GET", "/v1/summary/weekly?weekStart=2026-11-23", { token });
    expect(await res.json()).toEqual({ focusMinutes: 0, blocks: 0, intercepts: 0, unlocks: 0, days: [] });
  });

  it("only includes the caller's sessions", async () => {
    const h = harness();
    const a = await h.login("a@b.co");
    const b = await h.login("c@d.co");
    await h.req("POST", "/v1/sessions", { token: a.token, body: session() });
    const res = await h.req("GET", "/v1/summary/weekly?weekStart=2026-11-23", { token: b.token });
    expect(((await res.json()) as { blocks: number }).blocks).toBe(0);
  });

  it("tz shifts which local day a session belongs to", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    // 2026-11-22T20:00Z is Monday 01:30 in Asia/Kolkata, Sunday in UTC.
    await h.req("POST", "/v1/sessions", {
      token,
      body: session({ start: "2026-11-22T20:00:00Z", end: "2026-11-22T20:30:00Z" }),
    });
    const utc = (await (await h.req("GET", "/v1/summary/weekly?weekStart=2026-11-23", { token })).json()) as { blocks: number };
    expect(utc.blocks).toBe(0);
    const ist = (await (await h.req("GET", "/v1/summary/weekly?weekStart=2026-11-23&tz=Asia/Kolkata", { token })).json()) as { blocks: number; days: string[] };
    expect(ist.blocks).toBe(1);
    expect(ist.days).toEqual(["2026-11-23"]);
  });

  it("validates query params", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    for (const q of ["", "?weekStart=2026-13-01", "?weekStart=2026-02-30", "?weekStart=23-11-2026", "?weekStart=2026-11-23&tz=Mars/Base"]) {
      const res = await h.req("GET", `/v1/summary/weekly${q}`, { token });
      expect(res.status, q).toBe(400);
      expect(await errorCode(res)).toBe("invalid_query");
    }
  });
});
