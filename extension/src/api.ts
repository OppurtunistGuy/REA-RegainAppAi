// Backend client (docs/CONTRACTS.md, Phase 9).
// Privacy: the extension only ever GETs /v1/plan and /v1/detectors with the bearer token, plus the two
// OTP sign-in calls from the options page. It never sends page URLs, page contents or browsing data.

import type { Plan } from "./types.js";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

type Fetch = typeof fetch;

export function apiUrl(base: string, path: string): string {
  return base.replace(/\/+$/, "") + path;
}

async function getJson<T>(f: Fetch, base: string, path: string, token: string): Promise<T | null> {
  const res = await f(apiUrl(base, path), {
    method: "GET",
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
    credentials: "omit",
    cache: "no-store",
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new ApiError(res.status, `GET ${path} failed: ${res.status}`);
  return (await res.json()) as T;
}

export function fetchPlan(base: string, token: string, f: Fetch = fetch): Promise<Plan | null> {
  return getJson<Plan>(f, base, "/v1/plan", token);
}

export function fetchDetectors(base: string, token: string, f: Fetch = fetch): Promise<unknown> {
  return getJson<unknown>(f, base, "/v1/detectors", token);
}

export async function otpStart(base: string, contact: string, f: Fetch = fetch): Promise<void> {
  const res = await f(apiUrl(base, "/v1/auth/otp/start"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ contact }),
    credentials: "omit",
  });
  if (!res.ok) throw new ApiError(res.status, `Could not send code (${res.status})`);
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
  if (!res.ok) throw new ApiError(res.status, `Code not accepted (${res.status})`);
  return (await res.json()) as { token: string; userId: string };
}
