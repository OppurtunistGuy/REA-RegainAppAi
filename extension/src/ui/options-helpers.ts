/** Host-permission pattern for the API origin (match patterns ignore ports). */
export function apiOrigin(base: string): string | null {
  try {
    const u = new URL(base);
    if (u.protocol !== "https:" && u.protocol !== "http:") return null;
    return `${u.protocol}//${u.hostname}/*`;
  } catch {
    return null;
  }
}
