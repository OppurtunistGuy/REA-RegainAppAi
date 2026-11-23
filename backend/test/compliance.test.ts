import { describe, expect, it, vi } from "vitest";
import { ConsoleOtpSender, HttpOtpSender, maskContact, otpSenderFromEnv, type OtpSender } from "../src/otp.js";
import { PROTECTED_PACKAGES } from "../src/schemas.js";
import { errorCode, harness, samplePlan } from "./helpers.js";

describe("OTP delivery (B7)", () => {
  it("masks contacts", () => {
    expect(maskContact("riya@gmail.com")).toBe("r***@g***.com");
    expect(maskContact("a@b.co")).toBe("a***@b***.co");
    expect(maskContact("+91 98000 01234")).toBe("••••1234");
  });

  it("console sender masks the contact", () => {
    const lines: string[] = [];
    new ConsoleOtpSender((l) => lines.push(l)).send("riya@gmail.com", "123456");
    expect(lines[0]).toContain("r***@g***.com");
    expect(lines[0]).not.toContain("riya@");
  });

  it("console sender only outside production; production needs a provider", () => {
    expect(otpSenderFromEnv({})).toBeInstanceOf(ConsoleOtpSender);
    expect(otpSenderFromEnv({ NODE_ENV: "test" })).toBeInstanceOf(ConsoleOtpSender);
    expect(otpSenderFromEnv({ NODE_ENV: "production" })).toBeNull();
    expect(otpSenderFromEnv({ NODE_ENV: "production", OTP_EMAIL_WEBHOOK_URL: "https://mail.example/send" })).toBeInstanceOf(
      HttpOtpSender,
    );
  });

  it("production without a sender → OTP start 503 otp_not_configured, nothing stored", async () => {
    const h = harness({ otpSender: null });
    const res = await h.req("POST", "/v1/auth/otp/start", { body: { contact: "a@b.co" } });
    expect(res.status).toBe(503);
    expect(await errorCode(res)).toBe("otp_not_configured");
    expect((h.db.prepare("SELECT COUNT(*) AS n FROM otp_codes").get() as { n: number }).n).toBe(0);
  });

  it("goes through the OtpSender interface; delivery failure → 502 without leaking the code", async () => {
    const send = vi.fn<OtpSender["send"]>();
    const h = harness({ otpSender: { name: "fake", send } });
    expect((await h.req("POST", "/v1/auth/otp/start", { body: { contact: "A@B.co" } })).status).toBe(204);
    expect(send).toHaveBeenCalledWith("a@b.co", "000000");
    const errors = vi.spyOn(console, "error").mockImplementation(() => {});
    send.mockRejectedValueOnce(new Error("provider down"));
    const res = await h.req("POST", "/v1/auth/otp/start", { body: { contact: "a@b.co" } });
    expect(res.status).toBe(502);
    expect(await errorCode(res)).toBe("otp_send_failed");
    expect(errors.mock.calls.flat().join(" ")).not.toContain("000000");
    errors.mockRestore();
  });

  it("HTTP sender posts to the channel's endpoint with the bearer token", async () => {
    const f = vi.fn(async () => new Response(null, { status: 202 }));
    const s = new HttpOtpSender({ emailUrl: "https://mail.example/send", smsUrl: "https://sms.example/send", token: "k", fetch: f as unknown as typeof fetch });
    await s.send("a@b.co", "123456");
    await s.send("+919800001234", "654321");
    const calls = f.mock.calls as unknown as [string, RequestInit][];
    expect(calls[0]![0]).toBe("https://mail.example/send");
    expect(JSON.parse(calls[0]![1].body as string)).toEqual({ to: "a@b.co", code: "123456", channel: "email", ttlMinutes: 10 });
    expect((calls[0]![1].headers as Record<string, string>).authorization).toBe("Bearer k");
    expect(calls[1]![0]).toBe("https://sms.example/send");
    await expect(new HttpOtpSender({ emailUrl: "https://x" }).send("+91", "1")).rejects.toThrow(/sms/);
  });
});

describe("protected apps (B15)", () => {
  it("plans cannot block settings, installers, the store, calls, emergency or Steady", async () => {
    const h = harness();
    const { token } = await h.login("riya@example.com");
    expect(PROTECTED_PACKAGES.size).toBe(8);
    for (const pkg of PROTECTED_PACKAGES) {
      const plan = { ...samplePlan(1), distractions: [{ id: `app:${pkg}`, mode: "block_app" }] };
      const res = await h.req("PUT", "/v1/plan", { token, body: plan });
      expect(res.status, pkg).toBe(400);
      expect(await errorCode(res)).toBe("protected_app");
    }
    expect((await h.req("GET", "/v1/plan", { token })).status).toBe(404);
    expect((await h.req("PUT", "/v1/plan", { token, body: samplePlan(1) })).status).toBe(200);
  });
});

describe("display names (B16)", () => {
  it("never derives a name from the email or phone", async () => {
    const h = harness();
    const a = await h.login("riya.sharma@example.com");
    const b = await h.login("+919800001234");
    const names = (h.db.prepare("SELECT name FROM users").all() as { name: string }[]).map((r) => r.name);
    expect(names).toEqual(["Your friend", "Your friend"]);
    // Given names are kept.
    const owner = await h.login("dev@example.com", "Dev");
    const { code } = (await (await h.req("POST", "/v1/keyholder/invites", { token: owner.token })).json()) as { code: string };
    const acc = await h.req("POST", `/v1/keyholder/invites/${code}/accept`, { token: a.token });
    expect(((await acc.json()) as { ownerName: string }).ownerName).toBe("Dev");
    expect(b.userId).not.toBe(a.userId);
  });
});
