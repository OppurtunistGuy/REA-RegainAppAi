import { describe, expect, it, vi } from "vitest";
import {
  ApiError,
  apiUrl,
  authMessage,
  createUnlockRequest,
  fetchDetectors,
  fetchPlan,
  getUnlockRequest,
  logout,
  otpStart,
  otpVerify,
} from "../src/api.js";
import type { PublishedStatus } from "../src/types.js";
import { describeStatus, formatDuration } from "../src/ui/format.js";
import { apiOrigin } from "../src/ui/options-helpers.js";

const res = (status: number, body?: unknown): Response =>
  new Response(body === undefined ? null : JSON.stringify(body), { status });

describe("api client", () => {
  it("joins urls", () => expect(apiUrl("http://x/", "/v1/plan")).toBe("http://x/v1/plan"));

  it("GETs plan with bearer token and nothing else", async () => {
    const f = vi.fn(async () => res(200, { version: 1, distractions: [], schedules: [] }));
    const plan = await fetchPlan("http://api", "tok", f as unknown as typeof fetch);
    expect(plan?.version).toBe(1);
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://api/v1/plan");
    expect(init.method).toBe("GET");
    expect(init.body).toBeUndefined();
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok");
    expect(init.credentials).toBe("omit");
  });

  it("404 -> null, other errors throw ApiError", async () => {
    expect(await fetchPlan("http://api", "t", (async () => res(404)) as unknown as typeof fetch)).toBeNull();
    await expect(fetchDetectors("http://api", "t", (async () => res(401)) as unknown as typeof fetch)).rejects.toBeInstanceOf(ApiError);
  });

  it("OTP start/verify post only the contact and code", async () => {
    const f = vi.fn(async (url: string) => (url.endsWith("start") ? res(204) : res(200, { token: "T", userId: "U" })));
    await otpStart("http://api", "a@b.c", f as unknown as typeof fetch);
    const out = await otpVerify("http://api", "a@b.c", "000000", f as unknown as typeof fetch);
    expect(out).toEqual({ token: "T", userId: "U" });
    expect(JSON.parse((f.mock.calls[0] as unknown as [string, RequestInit])[1].body as string)).toEqual({ contact: "a@b.c" });
    expect(JSON.parse((f.mock.calls[1] as unknown as [string, RequestInit])[1].body as string)).toEqual({ contact: "a@b.c", code: "000000" });
    await expect(otpVerify("http://api", "a", "1", (async () => res(400)) as unknown as typeof fetch)).rejects.toThrow(/Code not accepted/);
  });
});

describe("api client: unlock requests, logout, error codes", () => {
  it("POSTs exactly {surfaceId, reason} with the bearer token", async () => {
    const f = vi.fn(async () => res(201, { id: "r1", status: "pending" }));
    const out = await createUnlockRequest("http://api/", "tok", "youtube.shorts", "lecture clip", f as unknown as typeof fetch);
    expect(out).toEqual({ id: "r1", status: "pending" });
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://api/v1/unlock-requests");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ surfaceId: "youtube.shorts", reason: "lecture clip" });
    expect(Object.keys(JSON.parse(init.body as string) as object)).toEqual(["surfaceId", "reason"]);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok");
    expect(init.credentials).toBe("omit");
  });

  it("GETs the request status; 404 throws", async () => {
    const f = vi.fn(async () => res(200, { id: "r/1", status: "approved", until: "2026-11-24T11:00:00.000Z" }));
    expect(await getUnlockRequest("http://api", "t", "r/1", f as unknown as typeof fetch)).toMatchObject({ status: "approved" });
    expect((f.mock.calls[0] as unknown as [string])[0]).toBe("http://api/v1/unlock-requests/r%2F1");
    await expect(getUnlockRequest("http://api", "t", "x", (async () => res(404)) as unknown as typeof fetch)).rejects.toMatchObject({ status: 404 });
  });

  it("parses the error code (token_expired, no_keyholder)", async () => {
    const expired = (async () => res(401, { error: { code: "token_expired", message: "x" } })) as unknown as typeof fetch;
    const err = await fetchPlan("http://api", "t", expired).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 401, code: "token_expired", isAuth: true });
    const none = (async () => res(409, { error: { code: "no_keyholder", message: "x" } })) as unknown as typeof fetch;
    await expect(createUnlockRequest("http://api", "t", null, "r", none)).rejects.toMatchObject({ code: "no_keyholder", isAuth: false });
    await expect(
      fetchPlan("http://api", "t", (async () => new Response("oops", { status: 502 })) as unknown as typeof fetch),
    ).rejects.toMatchObject({ code: null });
    expect(authMessage("token_expired")).toContain("expired");
    expect(authMessage(null)).toContain("Signed out");
  });

  it("logout POSTs with the token and no body; a 401 is fine", async () => {
    const f = vi.fn(async () => res(204));
    await logout("http://api", "tok", f as unknown as typeof fetch);
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://api/v1/auth/logout");
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
    await logout("http://api", "tok", (async () => res(401)) as unknown as typeof fetch);
    await expect(logout("http://api", "tok", (async () => res(500)) as unknown as typeof fetch)).rejects.toBeInstanceOf(ApiError);
  });
});

describe("format", () => {
  const now = Date.parse("2026-11-24T10:45:00Z");
  const st = (over: Partial<PublishedStatus>): PublishedStatus => ({
    computedAt: now,
    active: false,
    scheduleId: null,
    label: null,
    strict: true,
    endsAt: null,
    blockedSurfaces: [],
    blockedDomains: [],
    next: null,
    signedIn: true,
    lastSyncAt: now - 120_000,
    lastSyncError: null,
    ...over,
  });
  it("durations", () => {
    expect(formatDuration(59_000)).toBe("1 min");
    expect(formatDuration(165 * 60_000)).toBe("2 h 45 min");
    expect(formatDuration(120 * 60_000)).toBe("2 h");
  });
  it("describes active, idle, no plan and signed out", () => {
    const active = describeStatus(st({ active: true, label: "Coaching", endsAt: now + 165 * 60_000 }), true, now);
    expect(active).toMatchObject({ state: "active", headline: "Coaching is on", timeLeft: "2 h 45 min left" });
    expect(active.sync).toBe("Synced 2 min ago");
    const idle = describeStatus(
      st({ next: { scheduleId: "n", label: "Night study", startsAt: now + 3_600_000, endsAt: now + 7_200_000 } }),
      true,
      now,
    );
    expect(idle.state).toBe("idle");
    expect(idle.next).toMatch(/^Night study · /);
    expect(describeStatus(st({}), false, now).state).toBe("no-plan");
    expect(describeStatus(st({ signedIn: false }), false, now).state).toBe("signed-out");
    expect(describeStatus(st({ lastSyncError: "offline" }), true, now).sync).toContain("offline");
  });
  it("api origin pattern", () => {
    expect(apiOrigin("https://api.steady.app/")).toBe("https://api.steady.app/*");
    expect(apiOrigin("http://localhost:8787")).toBe("http://localhost/*");
    expect(apiOrigin("ftp://x")).toBeNull();
    expect(apiOrigin("nope")).toBeNull();
  });
  it("release builds require https for the API", () => {
    expect(apiOrigin("http://localhost:8787", false)).toBeNull();
    expect(apiOrigin("http://api.example.com", false)).toBeNull();
    expect(apiOrigin("https://api.example.com:8443", false)).toBe("https://api.example.com/*");
  });
});
