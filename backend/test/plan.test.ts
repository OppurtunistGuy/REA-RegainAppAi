import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { PlanSchema } from "../src/schemas.js";
import { errorCode, harness, samplePlan } from "./helpers.js";

describe("plan", () => {
  it("GET before any PUT → 404", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const res = await h.req("GET", "/v1/plan", { token });
    expect(res.status).toBe(404);
    expect(await errorCode(res)).toBe("not_found");
  });

  it("first PUT must be version 1, then GET returns it", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const wrong = await h.req("PUT", "/v1/plan", { token, body: samplePlan(2) });
    expect(wrong.status).toBe(409);
    const wrongBody = (await wrong.json()) as { current: unknown; error: { code: string } };
    expect(wrongBody.error.code).toBe("version_conflict");
    expect(wrongBody.current).toBeNull();

    const put = await h.req("PUT", "/v1/plan", { token, body: samplePlan(1) });
    expect(put.status).toBe(200);
    expect(await put.json()).toEqual(samplePlan(1));
    const get = await h.req("GET", "/v1/plan", { token });
    expect(await get.json()).toEqual(samplePlan(1));
  });

  it("PUT must be stored version + 1, else 409 with current", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    await h.req("PUT", "/v1/plan", { token, body: samplePlan(1) });
    const v2 = { ...samplePlan(2), siteBlocks: ["youtube.com"] };
    expect((await h.req("PUT", "/v1/plan", { token, body: v2 })).status).toBe(200);

    for (const stale of [1, 2, 4]) {
      const res = await h.req("PUT", "/v1/plan", { token, body: samplePlan(stale) });
      expect(res.status).toBe(409);
      const body = (await res.json()) as { current: unknown };
      expect(body.current).toEqual(v2);
    }
    expect((await h.req("PUT", "/v1/plan", { token, body: samplePlan(3) })).status).toBe(200);
    const get = await h.req("GET", "/v1/plan", { token });
    expect(((await get.json()) as { version: number }).version).toBe(3);
  });

  it("plans are per user", async () => {
    const h = harness();
    const a = await h.login("a@b.co");
    const b = await h.login("c@d.co");
    await h.req("PUT", "/v1/plan", { token: a.token, body: samplePlan(1) });
    expect((await h.req("GET", "/v1/plan", { token: b.token })).status).toBe(404);
    expect((await h.req("PUT", "/v1/plan", { token: b.token, body: samplePlan(1) })).status).toBe(200);
  });

  it("accepts a minimal plan", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const minimal = { version: 1, distractions: [], schedules: [] };
    const res = await h.req("PUT", "/v1/plan", { token, body: minimal });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual(minimal);
  });

  it("accepts keyHolder: null", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const res = await h.req("PUT", "/v1/plan", { token, body: { ...samplePlan(1), keyHolder: null } });
    expect(res.status).toBe(200);
  });

  it("accepts the shared test-vector plan", () => {
    const vectors = JSON.parse(
      readFileSync(new URL("../../shared/schema/test-vectors.json", import.meta.url), "utf8"),
    ) as { plan: unknown };
    expect(PlanSchema.safeParse(vectors.plan).success).toBe(true);
  });

  const invalid: [string, (p: ReturnType<typeof samplePlan>) => unknown][] = [
    ["unknown top-level field", (p) => ({ ...p, screenText: "hello" })],
    ["unknown nested field", (p) => ({ ...p, schedules: [{ ...p.schedules[0], notes: "x" }] })],
    ["bad surface id", (p) => ({ ...p, distractions: [{ id: "tiktok", mode: "block_app" }] })],
    ["bad mode", (p) => ({ ...p, distractions: [{ id: "youtube.shorts", mode: "nuke" }] })],
    ["bad time", (p) => ({ ...p, schedules: [{ ...p.schedules[0], start: "24:00" }] })],
    ["day 8", (p) => ({ ...p, schedules: [{ ...p.schedules[0], days: [8] }] })],
    ["empty days", (p) => ({ ...p, schedules: [{ ...p.schedules[0], days: [] }] })],
    ["duplicate days", (p) => ({ ...p, schedules: [{ ...p.schedules[0], days: [1, 1] }] })],
    ["long label", (p) => ({ ...p, schedules: [{ ...p.schedules[0], label: "x".repeat(61) }] })],
    ["bad relation", (p) => ({ ...p, keyHolder: { id: "k", name: "A", relation: "boss" } })],
    ["waitSeconds > 600", (p) => ({ ...p, unlockPolicy: { waitSeconds: 601 } })],
    ["approvalMinutes 0", (p) => ({ ...p, unlockPolicy: { approvalMinutes: 0 } })],
    ["bad domain", (p) => ({ ...p, siteBlocks: ["https://instagram.com"] })],
    ["version 0", (p) => ({ ...p, version: 0 })],
    ["version float", (p) => ({ ...p, version: 1.5 })],
    ["missing schedules", ({ schedules: _s, ...rest }) => rest],
  ];
  for (const [name, mutate] of invalid) {
    it(`rejects invalid plan: ${name}`, async () => {
      const h = harness();
      const { token } = await h.login("a@b.co");
      const res = await h.req("PUT", "/v1/plan", { token, body: mutate(samplePlan(1)) });
      expect(res.status).toBe(400);
      expect(await errorCode(res)).toBe("invalid_body");
      expect((await h.req("GET", "/v1/plan", { token })).status).toBe(404);
    });
  }
});
