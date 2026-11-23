// Backend client (docs/CONTRACTS.md, Phase 9 + Phase 12).
// Privacy: with the bearer token the extension only
//   - GETs /v1/plan and /v1/detectors,
//   - POSTs /v1/unlock-requests with {surfaceId, reason} when the plan has a key holder to ask,
//     then GETs /v1/unlock-requests/{id} to see the answer,
//   - POSTs /v1/auth/logout (empty body) when you sign out.
// The options page also calls the two OTP sign-in endpoints with the contact and code you type.
// It never sends page URLs, page contents or browsing data.

import type { Plan } from "./types.js";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** `error.code` from the server's error envelope, when it sent one. */
    readonly code: string | null = null,
  ) {
    super(message);
  }

  /** 401: the token is unknown, revoked or expired. The user has to sign in again. */
  get isAuth(): boolean {
    return this.status === 401;
  }
}

type Fetch = typeof fetch;

export function apiUrl(base: string, path: string): string {
  return base.replace(/\/+$/, "") + path;
}

async function errorCode(res: Response): Promise<string | null> {
  try {
    const body = (await res.json()) as { error?: { code?: unknown } };
    return typeof body.error?.code === "string" ? body.error.code : null;
  } catch {
    return null;
  }
}

async function fail(res: Response, what: string): Promise<never> {
  const code = await errorCode(res);
  throw new ApiError(res.status, `${what} failed: ${res.status}${code ? ` ${code}` : ""}`, code);
}

async function getJson<T>(f: Fetch, base: string, path: string, token: string): Promise<T | null> {
  const res = await f(apiUrl(base, path), {
    method: "GET",
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
    credentials: "omit",
    cache: "no-store",
  });
  if (res.status === 404) return null;
  if (!res.ok) return fail(res, `GET ${path}`);
  return (await res.json()) as T;
}

async function postJson(f: Fetch, base: string, path: string, token: string, body: unknown): Promise<Response> {
  return f(apiUrl(base, path), {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
    credentials: "omit",
    cache: "no-store",
  });
}

export function fetchPlan(base: string, token: string, f: Fetch = fetch): Promise<Plan | null> {
  return getJson<Plan>(f, base, "/v1/plan", token);
}

export function fetchDetectors(base: string, token: string, f: Fetch = fetch): Promise<unknown> {
  return getJson<unknown>(f, base, "/v1/detectors", token);
}

export interface UnlockRequestStatus {
  id: string;
  status: "pending" | "approved" | "denied";
  /** ISO time, present when approved. */
  until?: string;
}

/** Sends exactly `{surfaceId, reason}`. Nothing about the page is included. */
export async function createUnlockRequest(
  base: string,
  token: string,
  surfaceId: string | null,
  reason: string,
  f: Fetch = fetch,
): Promise<UnlockRequestStatus> {
  const res = await postJson(f, base, "/v1/unlock-requests", token, { surfaceId, reason });
  if (!res.ok) return fail(res, "POST /v1/unlock-requests");
  return (await res.json()) as UnlockRequestStatus;
}

export async function getUnlockRequest(
  base: string,
  token: string,
  id: string,
  f: Fetch = fetch,
): Promise<UnlockRequestStatus> {
  const got = await getJson<UnlockRequestStatus>(f, base, `/v1/unlock-requests/${encodeURIComponent(id)}`, token);
  if (got === null) throw new ApiError(404, "Unlock request not found", "not_found");
  return got;
}

/** Revokes the token on the server. Best effort: callers forget the token either way. */
export async function logout(base: string, token: string, f: Fetch = fetch): Promise<void> {
  const res = await f(apiUrl(base, "/v1/auth/logout"), {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    credentials: "omit",
  });
  if (!res.ok && res.status !== 401) return fail(res, "POST /v1/auth/logout");
}

export async function otpStart(base: string, contact: string, f: Fetch = fetch): Promise<void> {
  const res = await f(apiUrl(base, "/v1/auth/otp/start"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ contact }),
    credentials: "omit",
  });
  if (!res.ok) throw new ApiError(res.status, `Could not send code (${res.status})`, await errorCode(res));
}

export async function otpVerify(
  base: string,
  contact: string,
  code: string,
  f: Fetch = fetch,
): Promise<{ token: string; userId: string }> {
  const res = await f(apiUrl(base, "/v1/auth/otp/verify"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ contact, code }),
    credentials: "omit",
  });
  if (!res.ok) throw new ApiError(res.status, `Code not accepted (${res.status})`, await errorCode(res));
  return (await res.json()) as { token: string; userId: string };
}

/** Wording for a 401, shown in popup and options. */
export function authMessage(code: string | null): string {
  return code === "token_expired"
    ? "Your sign-in expired after 90 days without use. Sign in again in Options."
    : "Signed out on the server. Sign in again in Options.";
}
