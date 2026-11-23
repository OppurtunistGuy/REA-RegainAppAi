// Pure policy: turn (plan, grants, now) into the published status and the declarativeNetRequest rule set.
// No chrome.* calls here so it can be unit tested.

import { RulesEngine, hasGrant } from "./rules.js";
import type { Plan, PublishedStatus, UnlockGrant } from "./types.js";

/** Surfaces that content scripts / path rules handle on the web. */
export const WEB_SURFACES = ["youtube.shorts", "instagram.reels"] as const;
export type WebSurface = (typeof WEB_SURFACES)[number];

const DOMAIN_RE = /^[a-z0-9.-]+\.[a-z]{2,}$/;

/** "https://www.Example.com/x" -> "example.com"; returns null for anything that is not a bare domain. */
export function normalizeDomain(input: string): string | null {
  let d = input.trim().toLowerCase();
  d = d.replace(/^[a-z]+:\/\//, "").replace(/[/?#].*$/, "").replace(/:\d+$/, "");
  d = d.replace(/^www\./, "").replace(/\.$/, "");
  return DOMAIN_RE.test(d) ? d : null;
}

/** Every domain the plan asks to block: siteBlocks plus site:* surfaces, normalized and de-duplicated. */
export function planDomains(plan: Plan): string[] {
  const raw = [
    ...(plan.siteBlocks ?? []),
    ...plan.distractions.filter((s) => s.id.startsWith("site:")).map((s) => s.id.slice(5)),
  ];
  const out = new Set<string>();
  for (const r of raw) {
    const d = normalizeDomain(r);
    if (d) out.add(d);
  }
  return [...out].sort();
}

export interface StatusMeta {
  signedIn: boolean;
  lastSyncAt: number | null;
  lastSyncError: string | null;
}

export function computeStatus(
  plan: Plan | null,
  grants: UnlockGrant[],
  now: number,
  zone: string,
  meta: StatusMeta,
): PublishedStatus {
  const base: PublishedStatus = {
    computedAt: now,
    active: false,
    scheduleId: null,
    label: null,
    strict: false,
    endsAt: null,
    blockedSurfaces: [],
    blockedDomains: [],
    next: null,
    ...meta,
  };
  if (!plan) return base;
  const engine = new RulesEngine(zone);
  const st = engine.state(plan, now);
  base.next = engine.nextBlock(plan, now);
  if (st.kind !== "active") return base;
  return {
    ...base,
    active: true,
    scheduleId: st.scheduleId,
    label: st.label,
    strict: st.strict,
    endsAt: st.endsAt,
    blockedSurfaces: st.blocked.map((s) => s.id).filter((id) => !hasGrant(grants, id, now)),
    blockedDomains: planDomains(plan).filter((d) => !hasGrant(grants, `site:${d}`, now)),
  };
}

/** Earliest future instant at which the status can change (block start/end or grant expiry). */
export function nextTransition(status: PublishedStatus, grants: UnlockGrant[], now: number): number | null {
  const times = [status.endsAt, status.next?.startsAt ?? null, ...grants.map((g) => g.until)].filter(
    (t): t is number => t !== null && t > now,
  );
  return times.length ? Math.min(...times) : null;
}

export function pruneGrants(grants: UnlockGrant[], now: number): UnlockGrant[] {
  return grants.filter((g) => g.until > now);
}

// ---------- declarativeNetRequest ----------

export const SITE_RULE_BASE = 1000;
export const SURFACE_RULE_BASE = 100;

/** URL patterns (RE2) for the full-page parts of each web surface. Kept in sync with content/selectors.ts. */
export const SURFACE_URL_REGEX: Record<WebSurface, { site: string; regex: string }> = {
  "youtube.shorts": { site: "youtube.com", regex: "^https?://([a-z0-9-]+\\.)*youtube\\.com/shorts(/.*)?$" },
  "instagram.reels": { site: "instagram.com", regex: "^https?://([a-z0-9-]+\\.)*instagram\\.com/reels?(/.*)?$" },
};

/** Builds `interstitial.html?...`. The original URL always goes last, raw or encoded, as `url=`. */
export function interstitialQuery(surface: string, site: string, url?: string): string {
  const q = `surface=${encodeURIComponent(surface)}&site=${encodeURIComponent(site)}`;
  return url === undefined ? q : `${q}&url=${url}`;
}

export interface DnrInput {
  extensionId: string;
  blockedDomains: string[];
  blockedSurfaces: string[];
  /** Whether we hold host permission for the domain (needed for redirect). Without it we fall back to block. */
  canRedirect: (domain: string) => boolean;
}

export function buildDnrRules(input: DnrInput): chrome.declarativeNetRequest.Rule[] {
  const base = `chrome-extension://${input.extensionId}/interstitial.html?`;
  const rules: chrome.declarativeNetRequest.Rule[] = [];

  WEB_SURFACES.forEach((surface, i) => {
    if (!input.blockedSurfaces.includes(surface)) return;
    const { site, regex } = SURFACE_URL_REGEX[surface];
    rules.push({
      id: SURFACE_RULE_BASE + i,
      priority: 1,
      action: {
        type: "redirect" as chrome.declarativeNetRequest.RuleActionType,
        redirect: { regexSubstitution: base + interstitialQuery(surface, site, "\\0") },
      },
      condition: {
        regexFilter: regex,
        resourceTypes: ["main_frame" as chrome.declarativeNetRequest.ResourceType],
      },
    });
  });

  input.blockedDomains.forEach((domain, i) => {
    const redirect = input.canRedirect(domain);
    rules.push({
      id: SITE_RULE_BASE + i,
      priority: 2, // a whole-site block beats a surface rule on the same site
      action: redirect
        ? {
            type: "redirect" as chrome.declarativeNetRequest.RuleActionType,
            redirect: { regexSubstitution: base + interstitialQuery(`site:${domain}`, domain, "\\0") },
          }
        : { type: "block" as chrome.declarativeNetRequest.RuleActionType },
      condition: {
        ...(redirect ? { regexFilter: "^https?://.*$" } : {}),
        requestDomains: [domain],
        resourceTypes: ["main_frame" as chrome.declarativeNetRequest.ResourceType],
      },
    });
  });

  return rules;
}

/** Minimal shape check for a plan coming off the network (the server validates strictly). */
export function isPlan(x: unknown): x is Plan {
  if (!x || typeof x !== "object") return false;
  const p = x as Record<string, unknown>;
  const hhmm = /^([01]\d|2[0-3]):[0-5]\d$/;
  const isSchedule = (s: unknown): boolean => {
    if (!s || typeof s !== "object") return false;
    const o = s as Record<string, unknown>;
    return (
      typeof o.id === "string" &&
      typeof o.label === "string" &&
      Array.isArray(o.days) &&
      o.days.every((d) => Number.isInteger(d) && (d as number) >= 1 && (d as number) <= 7) &&
      typeof o.start === "string" &&
      hhmm.test(o.start) &&
      typeof o.end === "string" &&
      hhmm.test(o.end)
    );
  };
  return (
    typeof p.version === "number" &&
    Array.isArray(p.distractions) &&
    p.distractions.every((s) => !!s && typeof (s as { id?: unknown }).id === "string") &&
    Array.isArray(p.schedules) &&
    p.schedules.every(isSchedule) &&
    (p.siteBlocks === undefined ||
      (Array.isArray(p.siteBlocks) && p.siteBlocks.every((d) => typeof d === "string")))
  );
}

/** Host-permission origin patterns for a domain and its subdomains. */
export function originsFor(domain: string): string[] {
  return [`*://${domain}/*`, `*://*.${domain}/*`];
}
