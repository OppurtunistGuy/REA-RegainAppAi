import { readFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { mkdtempSync, writeFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { migrate, openDb, SCHEMA_VERSION } from "../src/db.js";
import { setTier } from "../src/entitlements.js";
import { errorCode, harness } from "./helpers.js";

describe("detectors", () => {
  it("serves shared/detectors/detectors.v1.json by default (no auth needed)", async () => {
    const h = harness();
    const res = await h.req("GET", "/v1/detectors");
    expect(res.status).toBe(200);
    const expected = JSON.parse(
      readFileSync(new URL("../../shared/detectors/detectors.v1.json", import.meta.url), "utf8"),
    ) as unknown;
    expect(await res.json()).toEqual(expected);
  });

  it("path is configurable", async () => {
    const dir = mkdtempSync(join(tmpdir(), "steady-det-"));
    const file = join(dir, "d.json");
    writeFileSync(file, JSON.stringify({ version: 7, detectors: [] }));
    const h = harness({ detectorsPath: file });
    expect(await (await h.req("GET", "/v1/detectors")).json()).toEqual({ version: 7, detectors: [] });
  });

  it("missing file → 503", async () => {
    const h = harness({ detectorsPath: "/nonexistent/detectors.json" });
    const res = await h.req("GET", "/v1/detectors");
    expect(res.status).toBe(503);
    expect(await errorCode(res)).toBe("detectors_unavailable");
  });
});

describe("entitlements", () => {
  it("defaults to free with no features", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    expect(await (await h.req("GET", "/v1/entitlements", { token })).json()).toEqual({ tier: "free", features: [] });
  });

  it("plus via setTier, and back", async () => {
    const h = harness();
    const { token, userId } = await h.login("a@b.co");
    setTier(h.db, userId, "plus");
    expect(await (await h.req("GET", "/v1/entitlements", { token })).json()).toEqual({
      tier: "plus",
      features: ["unlimited_schedules", "desktop_sync", "notes_to_cards", "task_breakdown"],
    });
    setTier(h.db, userId, "free");
    expect(((await (await h.req("GET", "/v1/entitlements", { token })).json()) as { tier: string }).tier).toBe("free");
  });
});

describe("cors", () => {
  it("allows chrome-extension origins", async () => {
    const h = harness();
    const origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";
    const pre = await h.req("OPTIONS", "/v1/plan", {
      headers: { Origin: origin, "Access-Control-Request-Method": "PUT", "Access-Control-Request-Headers": "authorization,content-type" },
    });
    expect(pre.status).toBe(204);
    expect(pre.headers.get("access-control-allow-origin")).toBe(origin);
    expect(pre.headers.get("access-control-allow-headers")?.toLowerCase()).toContain("authorization");
    const get = await h.req("GET", "/v1/detectors", { headers: { Origin: origin } });
    expect(get.headers.get("access-control-allow-origin")).toBe(origin);
  });

  it("does not allow arbitrary web origins", async () => {
    const h = harness();
    const res = await h.req("GET", "/v1/detectors", { headers: { Origin: "https://evil.example" } });
    expect(res.headers.get("access-control-allow-origin")).toBeNull();
  });

  it("allows configured extra origins", async () => {
    const h = harness({ corsOrigins: ["http://localhost:5173"] });
    const res = await h.req("GET", "/v1/detectors", { headers: { Origin: "http://localhost:5173" } });
    expect(res.headers.get("access-control-allow-origin")).toBe("http://localhost:5173");
  });
});

describe("errors and limits", () => {
  it("unknown route → 404 error envelope", async () => {
    const h = harness();
    const res = await h.req("GET", "/v1/nope");
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: { code: "not_found", message: "Not found" } });
  });

  it("oversized bodies → 413", async () => {
    const h = harness();
    const { token } = await h.login("a@b.co");
    const res = await h.req("POST", "/v1/sessions", { token, raw: JSON.stringify({ id: "x".repeat(70_000) }) });
    expect(res.status).toBe(413);
    expect(await errorCode(res)).toBe("body_too_large");
  });

  it("health endpoint", async () => {
    const h = harness();
    expect(await (await h.req("GET", "/v1/health")).json()).toEqual({ ok: true });
  });
});

describe("db", () => {
  it("migrations are idempotent and versioned", () => {
    const db = openDb(":memory:");
    migrate(db);
    const v = db.prepare("PRAGMA user_version").get() as { user_version: number };
    expect(v.user_version).toBe(SCHEMA_VERSION);
    const tables = (db.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all() as { name: string }[])
      .map((t) => t.name)
      .filter((n) => !n.startsWith("sqlite_"));
    expect(tables).toEqual([
      "entitlements",
      "invites",
      "keyholder_links",
      "otp_codes",
      "plans",
      "sessions",
      "tokens",
      "unlock_requests",
      "users",
    ]);
  });
});
