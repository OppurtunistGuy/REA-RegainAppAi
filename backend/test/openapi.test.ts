import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";
import { harness } from "./helpers.js";

const spec = parse(
  readFileSync(new URL("../../shared/api/openapi.yaml", import.meta.url), "utf8"),
) as { openapi: string; paths: Record<string, Record<string, unknown>> };

const CONTRACT = [
  "POST /v1/auth/otp/start",
  "POST /v1/auth/otp/verify",
  "GET /v1/plan",
  "PUT /v1/plan",
  "GET /v1/detectors",
  "POST /v1/keyholder/invites",
  "POST /v1/keyholder/invites/{code}/accept",
  "POST /v1/unlock-requests",
  "GET /v1/unlock-requests/{id}",
  "GET /v1/keyholder/requests",
  "POST /v1/unlock-requests/{id}/decision",
  "POST /v1/sessions",
  "GET /v1/summary/weekly",
  "GET /v1/entitlements",
].sort();

describe("openapi.yaml", () => {
  it("is OpenAPI 3.1 and lists exactly the CONTRACTS Phase 9 endpoints", () => {
    expect(spec.openapi).toBe("3.1.0");
    const documented = Object.entries(spec.paths)
      .flatMap(([p, ops]) => Object.keys(ops).map((m) => `${m.toUpperCase()} ${p}`))
      .sort();
    expect(documented).toEqual(CONTRACT);
  });

  it("every contract endpoint is implemented by the app", () => {
    const { app } = harness();
    const implemented = new Set(
      app.routes
        .filter((r) => r.method !== "ALL")
        .map((r) => `${r.method} ${r.path.replace(/:(\w+)/g, "{$1}")}`),
    );
    for (const e of CONTRACT) expect(implemented, e).toContain(e);
  });
});
