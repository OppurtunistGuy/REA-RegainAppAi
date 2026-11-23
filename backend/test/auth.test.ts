import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { errorCode, harness } from "./helpers.js";

describe("auth", () => {
  it("start returns 204 and sends 000000 in test mode", async () => {
    const h = harness();
    const res = await h.req("POST", "/v1/auth/otp/start", { body: { contact: "Riya@Example.com " } });
    expect(res.status).toBe(204);
    expect(h.sent).toEqual([{ contact: "riya@example.com", code: "000000" }]);
  });

  it("verify returns token + userId and token authenticates", async () => {
    const h = harness();
    const { token, userId } = await h.login("riya@example.com");
    expect(token).toMatch(/^[A-Za-z0-9_-]{40,}$/);
    expect(userId).toMatch(/[0-9a-f-]{36}/);
    const res = await h.req("GET", "/v1/entitlements", { token });
    expect(res.status).toBe(200);
  });

  it("same contact maps to the same user on re-login", async () => {
    const h = harness();
    const a = await h.login("riya@example.com");
    const b = await h.login("RIYA@example.com");
    expect(b.userId).toBe(a.userId);
    expect(b.token).not.toBe(a.token);
  });

  it("stores tokens and codes hashed, never in clear", async () => {
    const h = harness();
    const { token } = await h.login("riya@example.com");
    const tokens = h.db.prepare("SELECT token_hash FROM tokens").all() as { token_hash: string }[];
    expect(tokens).toHaveLength(1);
    expect(tokens[0]!.token_hash).toBe(createHash("sha256").update(token).digest("hex"));
    expect(tokens[0]!.token_hash).not.toContain(token);
    const codes = h.db.prepare("SELECT code_hash FROM otp_codes").all() as { code_hash: string }[];
    expect(codes[0]!.code_hash).not.toBe("000000");
    expect(codes[0]!.code_hash).toHaveLength(64);
  });

  it("random 6-digit code outside test mode, delivered via otpSender", async () => {
    const h = harness({ fixedOtp: false });
    await h.req("POST", "/v1/auth/otp/start", { body: { contact: "+919800000000" } });
    const code = h.sent[0]!.code;
    expect(code).toMatch(/^[0-9]{6}$/);
    const ok = await h.req("POST", "/v1/auth/otp/verify", { body: { contact: "+919800000000", code } });
    expect(ok.status).toBe(200);
  });

  it("wrong code → 401 invalid_code; code is single use", async () => {
    const h = harness();
    await h.req("POST", "/v1/auth/otp/start", { body: { contact: "a@b.co" } });
    const bad = await h.req("POST", "/v1/auth/otp/verify", { body: { contact: "a@b.co", code: "123456" } });
    expect(bad.status).toBe(401);
    expect(await errorCode(bad)).toBe("invalid_code");
    const ok = await h.req("POST", "/v1/auth/otp/verify", { body: { contact: "a@b.co", code: "000000" } });
    expect(ok.status).toBe(200);
    const again = await h.req("POST", "/v1/auth/otp/verify", { body: { contact: "a@b.co", code: "000000" } });
    expect(again.status).toBe(401);
  });

  it("locks the code after 5 wrong attempts", async () => {
    const h = harness();
    await h.req("POST", "/v1/auth/otp/start", { body: { contact: "a@b.co" } });
    for (let i = 0; i < 5; i++) {
      await h.req("POST", "/v1/auth/otp/verify", { body: { contact: "a@b.co", code: "111111" } });
    }
    const res = await h.req("POST", "/v1/auth/otp/verify", { body: { contact: "a@b.co", code: "000000" } });
    expect(res.status).toBe(401);
  });

  it("expired code → 401", async () => {
    const h = harness();
    await h.req("POST", "/v1/auth/otp/start", { body: { contact: "a@b.co" } });
    h.clock.advance(11 * 60_000);
    const res = await h.req("POST", "/v1/auth/otp/verify", { body: { contact: "a@b.co", code: "000000" } });
    expect(res.status).toBe(401);
  });

  it("verify with no prior start → 401", async () => {
    const h = harness();
    const res = await h.req("POST", "/v1/auth/otp/verify", { body: { contact: "a@b.co", code: "000000" } });
    expect(res.status).toBe(401);
  });

  it("rate-limits OTP start to 5 per contact per hour", async () => {
    const h = harness();
    for (let i = 0; i < 5; i++) {
      const r = await h.req("POST", "/v1/auth/otp/start", { body: { contact: "a@b.co" } });
      expect(r.status).toBe(204);
    }
    const sixth = await h.req("POST", "/v1/auth/otp/start", { body: { contact: "a@b.co" } });
    expect(sixth.status).toBe(429);
    expect(await errorCode(sixth)).toBe("rate_limited");
    // Other contacts are unaffected.
    expect((await h.req("POST", "/v1/auth/otp/start", { body: { contact: "c@d.co" } })).status).toBe(204);
    // Window slides.
    h.clock.advance(60 * 60_000 + 1);
    expect((await h.req("POST", "/v1/auth/otp/start", { body: { contact: "a@b.co" } })).status).toBe(204);
  });

  it("validates bodies strictly", async () => {
    const h = harness();
    const extra = await h.req("POST", "/v1/auth/otp/start", { body: { contact: "a@b.co", device: "x" } });
    expect(extra.status).toBe(400);
    expect(await errorCode(extra)).toBe("invalid_body");
    const missing = await h.req("POST", "/v1/auth/otp/start", { body: {} });
    expect(missing.status).toBe(400);
    const badJson = await h.req("POST", "/v1/auth/otp/start", { raw: "{nope" });
    expect(badJson.status).toBe(400);
    expect(await errorCode(badJson)).toBe("invalid_json");
    const badCode = await h.req("POST", "/v1/auth/otp/verify", { body: { contact: "a@b.co", code: "12" } });
    expect(badCode.status).toBe(400);
  });

  it("rejects missing, malformed and unknown bearer tokens", async () => {
    const h = harness();
    const paths: [string, string][] = [
      ["GET", "/v1/plan"],
      ["PUT", "/v1/plan"],
      ["POST", "/v1/keyholder/invites"],
      ["POST", "/v1/keyholder/invites/ABCDEF/accept"],
      ["GET", "/v1/keyholder/requests"],
      ["POST", "/v1/unlock-requests"],
      ["GET", "/v1/unlock-requests/x"],
      ["POST", "/v1/unlock-requests/x/decision"],
      ["POST", "/v1/sessions"],
      ["GET", "/v1/summary/weekly?weekStart=2026-11-23"],
      ["GET", "/v1/entitlements"],
    ];
    for (const [m, p] of paths) {
      const none = await h.req(m, p);
      expect(none.status, `${m} ${p}`).toBe(401);
      expect(await errorCode(none)).toBe("unauthorized");
      const unknown = await h.req(m, p, { token: "x".repeat(43) });
      expect(unknown.status, `${m} ${p}`).toBe(401);
    }
    const malformed = await h.req("GET", "/v1/plan", { headers: { Authorization: "Basic abc" } });
    expect(malformed.status).toBe(401);
  });
});
