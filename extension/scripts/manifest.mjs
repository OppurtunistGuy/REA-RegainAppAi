// Turns static/manifest.json into the shipped manifest.
// - production (`npm run build`): the API origin from STEADY_API_BASE (default https://api.steady.app),
//   https only, no localhost hosts;
// - development (`npm run build:dev`): also http://localhost and http://127.0.0.1 for a local backend.

export const DEFAULT_PROD_API_BASE = "https://api.steady.app";
export const DEV_API_BASE = "http://localhost:8787";
const DEV_HOSTS = ["http://localhost/*", "http://127.0.0.1/*"];

/** Match pattern for an API base URL (match patterns ignore ports). */
export function apiHostPattern(apiBase, { dev }) {
  let u;
  try {
    u = new URL(apiBase);
  } catch {
    throw new Error(`STEADY_API_BASE is not a URL: ${apiBase}`);
  }
  if (u.protocol !== "https:" && !(dev && u.protocol === "http:")) {
    throw new Error(`Production builds need an https:// API base (got ${u.protocol}//${u.host})`);
  }
  if (!dev && (u.hostname === "localhost" || u.hostname === "127.0.0.1")) {
    throw new Error("Production builds cannot point at localhost; use npm run build:dev");
  }
  return `${u.protocol}//${u.hostname}/*`;
}

export function buildManifest(template, { dev, apiBase }) {
  const m = structuredClone(template);
  const hosts = (m.host_permissions ?? []).filter((h) => !/^https?:\/\/(localhost|127\.0\.0\.1)\//.test(h));
  hosts.push(apiHostPattern(apiBase, { dev }));
  if (dev) hosts.push(...DEV_HOSTS);
  m.host_permissions = [...new Set(hosts)];
  if (dev) m.name = `${m.name} (dev)`;
  return m;
}
