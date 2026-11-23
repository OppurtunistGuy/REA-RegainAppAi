// Bootstrap shared by content scripts: follow the published status and switch the guard on/off.

import { interstitialQuery } from "../policy.js";
import type { PublishedStatus } from "../types.js";
import { SurfaceGuard, type GuardEnv } from "./guard.js";
import { withOverrides, type SurfaceConfig } from "./selectors.js";

export function isSurfaceActive(status: PublishedStatus | null, surfaceId: string, now: number): boolean {
  if (!status || !status.active) return false;
  if (status.endsAt !== null && now >= status.endsAt) return false;
  return status.blockedSurfaces.includes(surfaceId);
}

function browserEnv(extraNavEvents: string[]): GuardEnv {
  return {
    doc: document,
    href: () => location.href,
    redirect: (url) => location.replace(url),
    interstitialUrl: (surface, site, href) =>
      chrome.runtime.getURL(`interstitial.html?${interstitialQuery(surface, site, encodeURIComponent(href))}`),
    onNavigate: (cb) => {
      const handler = (): void => cb();
      const events = ["popstate", "hashchange", ...extraNavEvents];
      events.forEach((e) => window.addEventListener(e, handler, true));
      // Navigation API (Chrome 102+) sees SPA pushState navigations before they commit.
      const nav = (window as unknown as { navigation?: EventTarget }).navigation;
      const navHandler = (e: Event): void => {
        const url = (e as Event & { destination?: { url?: string } }).destination?.url;
        cb(url);
      };
      nav?.addEventListener("navigate", navHandler);
      return () => {
        events.forEach((e) => window.removeEventListener(e, handler, true));
        nav?.removeEventListener("navigate", navHandler);
      };
    },
  };
}

export function runContent(base: SurfaceConfig, extraNavEvents: string[] = []): void {
  if (!base.hosts.test(location.hostname)) return;
  let guard: SurfaceGuard | null = null;
  let endTimer: ReturnType<typeof setTimeout> | undefined;

  const apply = (status: PublishedStatus | null, detectors: unknown): void => {
    if (!guard) guard = new SurfaceGuard(withOverrides(base, detectors), browserEnv(extraNavEvents));
    const now = Date.now();
    guard.setActive(isSurfaceActive(status, base.surfaceId, now));
    clearTimeout(endTimer);
    if (status?.active && status.endsAt && status.endsAt > now) {
      endTimer = setTimeout(() => guard?.setActive(false), status.endsAt - now + 50);
    }
  };

  let detectors: unknown = null;
  void chrome.storage.local.get(["status", "detectors"]).then((got) => {
    detectors = (got.detectors as { value?: unknown } | undefined)?.value ?? null;
    apply((got.status as PublishedStatus | undefined) ?? null, detectors);
  });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local" || !changes.status) return;
    apply((changes.status.newValue as PublishedStatus | undefined) ?? null, detectors);
  });
}
