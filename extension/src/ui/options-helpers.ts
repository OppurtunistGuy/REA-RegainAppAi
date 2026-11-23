import { DEV_BUILD } from "../buildinfo.js";

/**
 * Host-permission pattern for the API origin (match patterns ignore ports), or null when the URL
 * is not allowed. Release builds accept https only; dev builds also accept http (local backend).
 */
export function apiOrigin(base: string, allowHttp: boolean = DEV_BUILD): string | null {
  try {
    const u = new URL(base);
    if (u.protocol !== "https:" && !(allowHttp && u.protocol === "http:")) return null;
    return `${u.protocol}//${u.hostname}/*`;
  } catch {
    return null;
  }
}
