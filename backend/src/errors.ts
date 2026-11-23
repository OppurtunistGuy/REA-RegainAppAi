import type { ContentfulStatusCode } from "hono/utils/http-status";

/** Thrown from handlers; rendered as `{error: {code, message}}` (plus optional extra fields). */
export class ApiError extends Error {
  constructor(
    readonly status: ContentfulStatusCode,
    readonly code: string,
    message: string,
    readonly extra?: Record<string, unknown>,
  ) {
    super(message);
  }
}

export const badRequest = (code: string, message: string) => new ApiError(400, code, message);
export const unauthorized = (message = "Missing or invalid bearer token") =>
  new ApiError(401, "unauthorized", message);
export const forbidden = (message: string) => new ApiError(403, "forbidden", message);
export const notFound = (message = "Not found") => new ApiError(404, "not_found", message);
export const conflict = (code: string, message: string, extra?: Record<string, unknown>) =>
  new ApiError(409, code, message, extra);
export const gone = (code: string, message: string) => new ApiError(410, code, message);
export const tooMany = (message: string) => new ApiError(429, "rate_limited", message);

export function errorBody(code: string, message: string, extra?: Record<string, unknown>) {
  return { error: { code, message }, ...(extra ?? {}) };
}
