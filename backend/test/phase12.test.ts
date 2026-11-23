import { describe, expect, it, vi } from "vitest";
import { createApp, TOKEN_IDLE_MS } from "../src/app.js";
import {
  DevPurchaseVerifier,
  GooglePlayVerifier,
  verifierFromEnv,
  type PurchaseVerifier,
} from "../src/billing.js";
import { migrate, SCHEMA_VERSION } from "../src/db.js";
import { setTier } from "../src/entitlements.js";
import { DatabaseSync } from "node:sqlite";
import { errorCode, FakeClock, harness, samplePlan, type Harness } from "./helpers.js";

const DAY = 86_400_000;

async function invite(h: Harness, token: string): Promise<string> {
  const res = await h.req("POST", "/v1/keyholder/invites", { token });
  return ((await res.json()) as { code: string }).code;
}

async function link(h: Harness, ownerToken: string, khToken: string) {
  const code = await invite(h, ownerToken);
  return h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: khToken });
}

describe("POST /v1/auth/logout", () => {
  it("revokes only the calling token", async () => {
    const h = harness();
    const a = await h.login("riya@example.com");
    const b = await h.login("riya@example.com");
    expect((await h.req("POST", "/v1/auth/logout", { token: a.token })).status).toBe(204);
    const after = await h.req("GET", "/v1/entitlements", { token: a.token });
    expect(after.status).toBe(401);
    expect(await errorCode(after)).toBe("unauthorized");
    expect((await h.req("GET", "/v1/entitlements", { token: b.token })).status).toBe(200);
  });

  it("needs a token and rejects body fields", async () => {
    const h = harness();
    expect((await h.req("POST", "/v1/auth/logout")).status).toBe(401);
    const { token } = await h.login("riya@example.com");
    expect((await h.req("POST", "/v1/auth/logout", { token, body: { all: true } })).status).toBe(400);
    expect((await h.req("POST", "/v1/auth/logout", { token, body: {} })).status).toBe(204);
  });
});

describe("sliding token expiry", () => {
  it("stays valid while used at least every 90 days", async () => {
    const h = harness();
    const { token } = await h.login("riya@example.com");
    for (let i = 0; i < 4; i++) {
      h.clock.advance(89 * DAY);
      expect((await h.req("GET", "/v1/entitlements", { token })).status).toBe(200);
    }
  });

  it("expires after 90 idle days with token_expired, then is gone", async () => {
    const h = harness();
    const { token } = await h.login("riya@example.com");
    h.clock.advance(TOKEN_IDLE_MS);
    const res = await h.req("GET", "/v1/plan", { token });
    expect(res.status).toBe(401);
    expect(await errorCode(res)).toBe("token_expired");
    expect((h.db.prepare("SELECT COUNT(*) AS n FROM tokens").get() as { n: number }).n).toBe(0);
    const again = await h.req("GET", "/v1/plan", { token });
    expect(await errorCode(again)).toBe("unauthorized");
  });

  it("refreshes last_used_at at most once a minute", async () => {
    const h = harness();
    const { token } = await h.login("riya@example.com");
    const lastUsed = () =>
      (h.db.prepare("SELECT last_used_at AS t FROM tokens").get() as { t: number }).t;
    const t0 = lastUsed();
    h.clock.advance(30_000);
    await h.req("GET", "/v1/entitlements", { token });
    expect(lastUsed()).toBe(t0);
    h.clock.advance(30_000);
    await h.req("GET", "/v1/entitlements", { token });
    expect(lastUsed()).toBe(t0 + 60_000);
  });
});

describe("migrations", () => {
  it("upgrades a v1 database: tokens get last_used_at, links get ids", async () => {
    const db = new DatabaseSync(":memory:");
    db.exec("PRAGMA foreign_keys = ON");
    migrate(db, 1);
    expect((db.prepare("PRAGMA user_version").get() as { user_version: number }).user_version).toBe(1);
    const created = Date.parse("2026-11-01T00:00:00Z");
    db.prepare("INSERT INTO users (id, contact, name, created_at) VALUES (?, ?, ?, ?)").run("u1", "a@x.co", "A", created);
    db.prepare("INSERT INTO users (id, contact, name, created_at) VALUES (?, ?, ?, ?)").run("u2", "b@x.co", "B", created);
    const { createHash } = await import("node:crypto");
    const token = "t".repeat(43);
    db.prepare("INSERT INTO tokens (token_hash, user_id, created_at) VALUES (?, ?, ?)").run(
      createHash("sha256").update(token).digest("hex"),
      "u1",
      created,
    );
    db.prepare("INSERT INTO keyholder_links (owner_user_id, keyholder_user_id, created_at) VALUES (?, ?, ?)").run("u1", "u2", created);

    migrate(db);
    expect((db.prepare("PRAGMA user_version").get() as { user_version: number }).user_version).toBe(SCHEMA_VERSION);
    expect(SCHEMA_VERSION).toBeGreaterThanOrEqual(2);
    const tok = db.prepare("SELECT created_at, last_used_at FROM tokens").get() as { created_at: number; last_used_at: number };
    expect(tok.last_used_at).toBe(created);
    const l = db.prepare("SELECT id, owner_user_id, keyholder_user_id FROM keyholder_links").get() as { id: string };
    expect(l.id).toMatch(/^[0-9a-f]{32}$/);

    // The upgraded DB serves requests: the old token works and the link is listed.
    const clock = new FakeClock(created + 10 * DAY);
    const app = createApp({ db, clock, otpSender: () => {} });
    const res = await app.request("/v1/keyholder/links", { headers: { Authorization: `Bearer ${token}` } });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual([
      { linkId: l.id, keyHolderUserId: "u2", keyHolderName: "B", createdAt: new Date(created).toISOString() },
    ]);
    // An old token idle for 90 days (from created_at) is expired.
    clock.ms = created + 10 * DAY + TOKEN_IDLE_MS;
    const exp = await app.request("/v1/plan", { headers: { Authorization: `Bearer ${token}` } });
    expect(exp.status).toBe(401);
  });

  it("refuses a database newer than the code", () => {
    const db = new DatabaseSync(":memory:");
    db.exec(`PRAGMA user_version = ${SCHEMA_VERSION + 1}`);
    expect(() => migrate(db)).toThrow(/newer/);
  });
});

describe("key-holder links", () => {
  it("owner and key holder each see the link; either side may remove it", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com", "Riya");
    const kh = await h.login("asha@example.com", "Asha");
    expect((await link(h, owner.token, kh.token)).status).toBe(200);

    const links = (await (await h.req("GET", "/v1/keyholder/links", { token: owner.token })).json()) as {
      linkId: string;
    }[];
    expect(links).toEqual([
      { linkId: expect.any(String), keyHolderUserId: kh.userId, keyHolderName: "Asha", createdAt: new Date(h.clock.ms).toISOString() },
    ]);
    const owners = await (await h.req("GET", "/v1/keyholder/owners", { token: kh.token })).json();
    expect(owners).toEqual([
      { linkId: links[0]!.linkId, ownerUserId: owner.userId, ownerName: "Riya", createdAt: new Date(h.clock.ms).toISOString() },
    ]);
    expect(await (await h.req("GET", "/v1/keyholder/links", { token: kh.token })).json()).toEqual([]);
    expect(await (await h.req("GET", "/v1/keyholder/owners", { token: owner.token })).json()).toEqual([]);

    // Key holder removes it.
    expect((await h.req("DELETE", `/v1/keyholder/links/${links[0]!.linkId}`, { token: kh.token })).status).toBe(204);
    expect(await (await h.req("GET", "/v1/keyholder/links", { token: owner.token })).json()).toEqual([]);
    const req = await h.req("POST", "/v1/unlock-requests", { token: owner.token, body: { surfaceId: null, reason: "Need it now please" } });
    expect(await errorCode(req)).toBe("no_keyholder");

    // Owner removes a fresh link.
    expect((await link(h, owner.token, kh.token)).status).toBe(200);
    const [l2] = (await (await h.req("GET", "/v1/keyholder/links", { token: owner.token })).json()) as { linkId: string }[];
    expect((await h.req("DELETE", `/v1/keyholder/links/${l2!.linkId}`, { token: owner.token })).status).toBe(204);
    expect((await h.req("DELETE", `/v1/keyholder/links/${l2!.linkId}`, { token: owner.token })).status).toBe(404);
  });

  it("strangers cannot remove a link (404)", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const kh = await h.login("asha@example.com");
    const stranger = await h.login("eve@example.com");
    await link(h, owner.token, kh.token);
    const [l] = (await (await h.req("GET", "/v1/keyholder/links", { token: owner.token })).json()) as { linkId: string }[];
    const res = await h.req("DELETE", `/v1/keyholder/links/${l!.linkId}`, { token: stranger.token });
    expect(res.status).toBe(404);
    expect((await h.req("DELETE", "/v1/keyholder/links/nope", { token: owner.token })).status).toBe(404);
    expect((await (await h.req("GET", "/v1/keyholder/links", { token: owner.token })).json()) as unknown[]).toHaveLength(1);
    expect((await h.req("GET", "/v1/keyholder/links")).status).toBe(401);
  });
});

describe("free tier key-holder limit", () => {
  it("a second key holder → 409 keyholder_limit and the invite stays usable", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const kh1 = await h.login("asha@example.com");
    const kh2 = await h.login("dev@example.com");
    expect((await link(h, owner.token, kh1.token)).status).toBe(200);

    const code = await invite(h, owner.token);
    const res = await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: kh2.token });
    expect(res.status).toBe(409);
    expect(await errorCode(res)).toBe("keyholder_limit");

    // Upgrading lifts the limit; the same invite was not consumed.
    setTier(h.db, owner.userId, "plus");
    expect((await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: kh2.token })).status).toBe(200);
    expect((await (await h.req("GET", "/v1/keyholder/links", { token: owner.token })).json()) as unknown[]).toHaveLength(2);
  });

  it("the existing key holder may accept again; removing the link frees the slot", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const kh1 = await h.login("asha@example.com");
    const kh2 = await h.login("dev@example.com");
    await link(h, owner.token, kh1.token);
    expect((await link(h, owner.token, kh1.token)).status).toBe(200);
    const [l] = (await (await h.req("GET", "/v1/keyholder/links", { token: owner.token })).json()) as { linkId: string }[];
    await h.req("DELETE", `/v1/keyholder/links/${l!.linkId}`, { token: owner.token });
    expect((await link(h, owner.token, kh2.token)).status).toBe(200);
  });

  it("the limit is per owner: one person can hold keys for many owners", async () => {
    const h = harness();
    const kh = await h.login("asha@example.com");
    for (const n of ["a", "b", "c"]) {
      const o = await h.login(`${n}@example.com`);
      expect((await link(h, o.token, kh.token)).status).toBe(200);
    }
    expect((await (await h.req("GET", "/v1/keyholder/owners", { token: kh.token })).json()) as unknown[]).toHaveLength(3);
  });
});

describe("GET /v1/keyholder/summary", () => {
  const session = (id: string, start: string, minutes: number) => ({
    id,
    scheduleId: "coaching",
    start,
    end: new Date(Date.parse(start) + minutes * 60_000).toISOString(),
    intercepts: 2,
    unlocks: 1,
  });

  it("a linked key holder sees the owner's weekly summary", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const kh = await h.login("asha@example.com");
    await link(h, owner.token, kh.token);
    await h.req("POST", "/v1/sessions", { token: owner.token, body: session("s1", "2026-11-23T10:00:00Z", 25) });
    await h.req("POST", "/v1/sessions", { token: owner.token, body: session("s2", "2026-11-24T10:00:00Z", 40) });
    const q = "weekStart=2026-11-23&tz=Asia/Kolkata";
    const own = await (await h.req("GET", `/v1/summary/weekly?${q}`, { token: owner.token })).json();
    const res = await h.req("GET", `/v1/keyholder/summary?ownerUserId=${owner.userId}&${q}`, { token: kh.token });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toEqual(own);
    expect(body).toEqual({ focusMinutes: 65, blocks: 2, intercepts: 4, unlocks: 2, days: ["2026-11-23", "2026-11-24"] });
  });

  it("404 for unlinked or unknown owners, including the owner asking about themselves", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const stranger = await h.login("eve@example.com");
    for (const [token, id] of [
      [stranger.token, owner.userId],
      [stranger.token, "00000000-0000-0000-0000-000000000000"],
      [owner.token, owner.userId],
    ] as const) {
      const res = await h.req("GET", `/v1/keyholder/summary?ownerUserId=${id}&weekStart=2026-11-23`, { token });
      expect(res.status).toBe(404);
    }
  });

  it("400 for missing owner or bad week", async () => {
    const h = harness();
    const owner = await h.login("riya@example.com");
    const kh = await h.login("asha@example.com");
    await link(h, owner.token, kh.token);
    expect((await h.req("GET", "/v1/keyholder/summary?weekStart=2026-11-23", { token: kh.token })).status).toBe(400);
    const bad = await h.req("GET", `/v1/keyholder/summary?ownerUserId=${owner.userId}&weekStart=2026-13-01`, { token: kh.token });
    expect(await errorCode(bad)).toBe("invalid_query");
    const tz = await h.req("GET", `/v1/keyholder/summary?ownerUserId=${owner.userId}&weekStart=2026-11-23&tz=Mars/Base`, { token: kh.token });
    expect(tz.status).toBe(400);
  });
});

describe("POST /v1/billing/verify", () => {
  it("dev verifier accepts test- tokens and upgrades to plus", async () => {
    const h = harness();
    const { token } = await h.login("riya@example.com");
    const res = await h.req("POST", "/v1/billing/verify", {
      token,
      body: { productId: "steady_plus_monthly", purchaseToken: "test-abc" },
    });
    expect(res.status).toBe(200);
    const plus = { tier: "plus", features: ["unlimited_schedules", "desktop_sync", "notes_to_cards", "task_breakdown"] };
    expect(await res.json()).toEqual(plus);
    expect(await (await h.req("GET", "/v1/entitlements", { token })).json()).toEqual(plus);
    // Re-verifying the same token (restore purchases) is fine.
    const again = await h.req("POST", "/v1/billing/verify", {
      token,
      body: { productId: "steady_plus_yearly", purchaseToken: "test-abc" },
    });
    expect(again.status).toBe(200);
    // Purchase tokens are stored hashed.
    const rows = h.db.prepare("SELECT token_hash FROM purchases").all() as { token_hash: string }[];
    expect(rows).toHaveLength(1);
    expect(rows[0]!.token_hash).not.toContain("test-abc");
  });

  it("rejects other tokens with purchase_invalid and leaves the tier alone", async () => {
    const h = harness();
    const { token } = await h.login("riya@example.com");
    const res = await h.req("POST", "/v1/billing/verify", {
      token,
      body: { productId: "steady_plus_monthly", purchaseToken: "real-looking-token" },
    });
    expect(res.status).toBe(400);
    expect(await errorCode(res)).toBe("purchase_invalid");
    expect(((await (await h.req("GET", "/v1/entitlements", { token })).json()) as { tier: string }).tier).toBe("free");
  });

  it("validates the body strictly", async () => {
    const h = harness();
    const { token } = await h.login("riya@example.com");
    for (const body of [
      { productId: "steady_plus_lifetime", purchaseToken: "test-1" },
      { productId: "steady_plus_monthly" },
      { productId: "steady_plus_monthly", purchaseToken: "test-1", orderId: "GPA.1" },
    ]) {
      const res = await h.req("POST", "/v1/billing/verify", { token, body });
      expect(res.status).toBe(400);
      expect(await errorCode(res)).toBe("invalid_body");
    }
    expect((await h.req("POST", "/v1/billing/verify", { body: { productId: "steady_plus_monthly", purchaseToken: "test-1" } })).status).toBe(401);
  });

  it("a purchase token cannot be claimed by a second account", async () => {
    const h = harness();
    const a = await h.login("riya@example.com");
    const b = await h.login("dev@example.com");
    const body = { productId: "steady_plus_monthly", purchaseToken: "test-shared" };
    expect((await h.req("POST", "/v1/billing/verify", { token: a.token, body })).status).toBe(200);
    const res = await h.req("POST", "/v1/billing/verify", { token: b.token, body });
    expect(res.status).toBe(409);
    expect(await errorCode(res)).toBe("purchase_in_use");
  });

  it("not configured → 503 billing_not_configured", async () => {
    const h = harness({ purchaseVerifier: null });
    const { token } = await h.login("riya@example.com");
    const res = await h.req("POST", "/v1/billing/verify", {
      token,
      body: { productId: "steady_plus_monthly", purchaseToken: "test-abc" },
    });
    expect(res.status).toBe(503);
    expect(await errorCode(res)).toBe("billing_not_configured");
  });

  it("goes through the PurchaseVerifier interface; store errors → 502", async () => {
    const verify = vi.fn<PurchaseVerifier["verify"]>().mockResolvedValueOnce({ valid: true, expiresAt: null });
    const h = harness({ purchaseVerifier: { name: "fake", verify } });
    const { token } = await h.login("riya@example.com");
    const body = { productId: "steady_plus_yearly", purchaseToken: "gp-token-1" };
    expect((await h.req("POST", "/v1/billing/verify", { token, body })).status).toBe(200);
    expect(verify).toHaveBeenCalledWith("steady_plus_yearly", "gp-token-1");
    verify.mockRejectedValueOnce(new Error("network"));
    const res = await h.req("POST", "/v1/billing/verify", { token, body });
    expect(res.status).toBe(502);
    expect(await errorCode(res)).toBe("billing_unavailable");
  });

  it("verifierFromEnv picks dev outside production, nothing in production, Play when configured", () => {
    expect(verifierFromEnv({ NODE_ENV: "test" })).toBeInstanceOf(DevPurchaseVerifier);
    expect(verifierFromEnv({})).toBeInstanceOf(DevPurchaseVerifier);
    expect(verifierFromEnv({ NODE_ENV: "production" })).toBeNull();
    const play = verifierFromEnv({
      NODE_ENV: "production",
      GOOGLE_PLAY_PACKAGE_NAME: "app.steady",
      GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: JSON.stringify({ client_email: "x@y.iam.gserviceaccount.com", private_key: "-----BEGIN PRIVATE KEY-----" }),
    });
    expect(play).toBeInstanceOf(GooglePlayVerifier);
    expect(() =>
      verifierFromEnv({ GOOGLE_PLAY_PACKAGE_NAME: "app.steady", GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: "{}" }),
    ).toThrow(/client_email/);
  });
});

describe("DELETE /v1/me", () => {
  it("erases every row about the user in every table and leaves others intact", async () => {
    const h = harness();
    const me = await h.login("riya@example.com", "Riya");
    const kh = await h.login("asha@example.com", "Asha");
    const other = await h.login("dev@example.com", "Dev");

    // Everything a user can create:
    await h.req("PUT", "/v1/plan", { token: me.token, body: samplePlan(1) });
    await h.req("POST", "/v1/sessions", {
      token: me.token,
      body: { id: "s1", scheduleId: null, start: "2026-11-23T08:00:00Z", end: "2026-11-23T08:30:00Z", intercepts: 1, unlocks: 0 },
    });
    await link(h, me.token, kh.token); // me as owner
    await link(h, other.token, me.token); // me as someone's key holder
    await invite(h, me.token); // unused invite
    await h.req("POST", "/v1/unlock-requests", { token: me.token, body: { surfaceId: null, reason: "Exam form closes" } });
    const otherReq = (await (
      await h.req("POST", "/v1/unlock-requests", { token: other.token, body: { surfaceId: null, reason: "Group call now" } })
    ).json()) as { id: string };
    await h.req("POST", `/v1/unlock-requests/${otherReq.id}/decision`, { token: me.token, body: { approve: true } });
    await h.req("POST", "/v1/billing/verify", {
      token: me.token,
      body: { productId: "steady_plus_monthly", purchaseToken: "test-me" },
    });
    await h.login("riya@example.com"); // a second token + otp row

    const res = await h.req("DELETE", "/v1/me", { token: me.token });
    expect(res.status).toBe(204);

    // Scan every column of every table for the user id or contact.
    const tables = (h.db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").all() as { name: string }[]).map((t) => t.name);
    expect(tables.length).toBeGreaterThanOrEqual(10);
    for (const t of tables) {
      const rows = h.db.prepare(`SELECT * FROM ${t}`).all() as Record<string, unknown>[];
      for (const row of rows) {
        const values = Object.values(row).map(String);
        expect(values, `${t} still holds the user`).not.toContain(me.userId);
        expect(values.join("|"), `${t} still holds the contact`).not.toContain("riya@example.com");
      }
    }
    const count = (t: string) => (h.db.prepare(`SELECT COUNT(*) AS n FROM ${t}`).get() as { n: number }).n;
    expect(count("plans")).toBe(0);
    expect(count("sessions")).toBe(0);
    expect(count("entitlements")).toBe(0);
    expect(count("purchases")).toBe(0);
    expect(count("keyholder_links")).toBe(0);
    expect(count("users")).toBe(2);

    // Old tokens are dead; other users keep their data.
    expect((await h.req("GET", "/v1/plan", { token: me.token })).status).toBe(401);
    const status = await (await h.req("GET", `/v1/unlock-requests/${otherReq.id}`, { token: other.token })).json();
    expect(status).toMatchObject({ id: otherReq.id, status: "approved" });
    expect((await h.req("GET", "/v1/entitlements", { token: kh.token })).status).toBe(200);

    // Signing in again with the same contact starts a brand-new account.
    const fresh = await h.login("riya@example.com");
    expect(fresh.userId).not.toBe(me.userId);
    expect((await h.req("GET", "/v1/plan", { token: fresh.token })).status).toBe(404);
  });

  it("rolls back everything if one delete fails", async () => {
    const h = harness();
    const me = await h.login("riya@example.com");
    await h.req("PUT", "/v1/plan", { token: me.token, body: samplePlan(1) });
    h.db.exec(`CREATE TRIGGER block_user_delete BEFORE DELETE ON users BEGIN SELECT RAISE(ABORT, 'nope'); END`);
    const res = await h.req("DELETE", "/v1/me", { token: me.token });
    expect(res.status).toBe(500);
    h.db.exec("DROP TRIGGER block_user_delete");
    expect((await h.req("GET", "/v1/plan", { token: me.token })).status).toBe(200);
  });
});

describe("hardening", () => {
  it("sets security headers, no-store and a request id", async () => {
    const h = harness();
    const res = await h.req("GET", "/v1/health");
    expect(res.headers.get("x-content-type-options")).toBe("nosniff");
    expect(res.headers.get("x-frame-options")).toBe("SAMEORIGIN");
    expect(res.headers.get("strict-transport-security")).toContain("max-age=");
    expect(res.headers.get("referrer-policy")).toBe("no-referrer");
    expect(res.headers.get("content-security-policy")).toContain("default-src 'none'");
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(res.headers.get("x-request-id")).toMatch(/^[0-9a-f-]{36}$/);
    const echoed = await h.req("GET", "/v1/health", { headers: { "X-Request-Id": "abc-12345" } });
    expect(echoed.headers.get("x-request-id")).toBe("abc-12345");
    const bogus = await h.req("GET", "/v1/health", { headers: { "X-Request-Id": "bad id with spaces" } });
    expect(bogus.headers.get("x-request-id")).not.toBe("bad id with spaces");
  });

  it("logs route patterns with request ids, never bodies, codes or tokens", async () => {
    const lines: string[] = [];
    const h = harness({ log: (l) => lines.push(l) });
    const owner = await h.login("riya@example.com");
    const kh = await h.login("asha@example.com");
    const code = await invite(h, owner.token);
    await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: kh.token });
    await h.req("GET", "/v1/nope");
    const all = lines.join("\n");
    expect(all).toContain("POST /v1/keyholder/invites/:code/accept 200");
    expect(all).toContain("GET (unmatched) 404");
    expect(all).toMatch(/rid=[0-9a-f-]{36}/);
    for (const secret of [code, owner.token, "riya@example.com", "000000"]) expect(all).not.toContain(secret);
  });

  it("rate limits OTP verify per client IP", async () => {
    const h = harness({ otpVerifyPerIp: 3, trustProxy: true });
    const verify = (ip: string) =>
      h.req("POST", "/v1/auth/otp/verify", {
        body: { contact: "a@b.co", code: "123456" },
        headers: { "X-Forwarded-For": `${ip}, 10.0.0.1` },
      });
    for (let i = 0; i < 3; i++) expect((await verify("203.0.113.5")).status).toBe(401);
    const limited = await verify("203.0.113.5");
    expect(limited.status).toBe(429);
    expect(await errorCode(limited)).toBe("rate_limited");
    expect((await verify("198.51.100.7")).status).toBe(401);
    h.clock.advance(15 * 60_000);
    expect((await verify("203.0.113.5")).status).toBe(401);
  });

  it("ignores X-Forwarded-For unless trustProxy is set", async () => {
    const h = harness({ otpVerifyPerIp: 2 });
    const verify = (ip: string) =>
      h.req("POST", "/v1/auth/otp/verify", { body: { contact: "a@b.co", code: "123456" }, headers: { "X-Forwarded-For": ip } });
    await verify("1.1.1.1");
    await verify("2.2.2.2");
    expect((await verify("3.3.3.3")).status).toBe(429);
  });

  it("CORS preflight allows DELETE from the extension", async () => {
    const h = harness();
    const origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";
    const pre = await h.req("OPTIONS", "/v1/me", {
      headers: { Origin: origin, "Access-Control-Request-Method": "DELETE" },
    });
    expect(pre.status).toBe(204);
    expect(pre.headers.get("access-control-allow-methods")).toContain("DELETE");
  });
});
