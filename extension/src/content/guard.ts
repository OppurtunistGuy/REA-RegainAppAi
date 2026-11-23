// Generic "hide this surface" engine shared by the YouTube and Instagram content scripts.
// It only looks at element tags/attributes and the current URL path, and never reads or sends page text.

import type { SurfaceConfig } from "./selectors.js";

export const HIDDEN_ATTR = "data-steady-hidden";
export const STYLE_ID = "steady-hide-style";

export function pathBlocked(pathname: string, prefixes: string[]): boolean {
  return prefixes.some((p) =>
    p.endsWith("/") ? pathname.startsWith(p) : pathname === p || pathname.startsWith(p + "/"),
  );
}

export function buildCss(config: SurfaceConfig): string {
  const sel = [...config.css, `[${HIDDEN_ATTR}]`].join(",\n");
  return `${sel} { display: none !important; }`;
}

export interface GuardEnv {
  doc: Document;
  /** Current URL of the page. */
  href: () => string;
  /** Leave the page for the interstitial (location.replace in production). */
  redirect: (url: string) => void;
  /** Absolute URL of the interstitial for this surface. */
  interstitialUrl: (surfaceId: string, site: string, originalHref: string) => string;
  /** Register a navigation listener; returns an unsubscribe function. */
  onNavigate: (cb: (nextHref?: string) => void) => () => void;
}

export class SurfaceGuard {
  private active = false;
  private observer: MutationObserver | null = null;
  private unNavigate: (() => void) | null = null;
  private lastHref = "";
  private redirected = false;

  constructor(
    private readonly config: SurfaceConfig,
    private readonly env: GuardEnv,
  ) {}

  isActive(): boolean {
    return this.active;
  }

  setActive(active: boolean): void {
    if (active === this.active) {
      if (active) this.checkLocation();
      return;
    }
    this.active = active;
    if (active) this.start();
    else this.stop();
  }

  /** Marks every element matched by the hide rules. Returns how many new elements were hidden. */
  hideNow(root: ParentNode = this.env.doc): number {
    let n = 0;
    for (const rule of this.config.hide) {
      let found: Element[];
      try {
        found = [...root.querySelectorAll(rule.selector)];
        if ((root as Element).nodeType === 1 && (root as Element).matches(rule.selector)) {
          found.push(root as Element);
        }
      } catch {
        continue; // selector not supported in this engine; CSS layer may still cover it
      }
      found.forEach((el) => {
        const target = (rule.closest && el.closest(rule.closest)) || el;
        if (!target.hasAttribute(HIDDEN_ATTR)) {
          target.setAttribute(HIDDEN_ATTR, "");
          n++;
        }
      });
    }
    return n;
  }

  /** Sends the tab to the interstitial if the URL is a blocked path. Returns true if it redirected. */
  checkLocation(nextHref?: string): boolean {
    if (!this.active || this.redirected) return false;
    const href = nextHref ?? this.env.href();
    this.lastHref = href;
    let path: string;
    try {
      path = new URL(href).pathname;
    } catch {
      return false;
    }
    if (!pathBlocked(path, this.config.blockedPathPrefixes)) return false;
    this.redirected = true;
    this.env.redirect(this.env.interstitialUrl(this.config.surfaceId, this.config.site, href));
    return true;
  }

  private start(): void {
    const { doc } = this.env;
    this.redirected = false;
    if (this.checkLocation()) return;
    if (!doc.getElementById(STYLE_ID)) {
      const style = doc.createElement("style");
      style.id = STYLE_ID;
      style.textContent = buildCss(this.config);
      (doc.head ?? doc.documentElement).appendChild(style);
    }
    this.hideNow();
    this.observer = new MutationObserver((records) => {
      if (this.env.href() !== this.lastHref && this.checkLocation()) return;
      for (const r of records) {
        if (r.type === "attributes") {
          this.hideNow(r.target as Element); // YouTube recycles cards and swaps their href
          continue;
        }
        r.addedNodes.forEach((node) => {
          if (node.nodeType !== 1) return;
          // The added node itself may match, or contain matches.
          this.hideNow(node as Element);
        });
      }
    });
    this.observer.observe(doc.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["href", "is-shorts", "aria-label", "tab-title"],
    });
    this.unNavigate = this.env.onNavigate((next) => this.checkLocation(next));
  }

  private stop(): void {
    this.observer?.disconnect();
    this.observer = null;
    this.unNavigate?.();
    this.unNavigate = null;
    this.env.doc.getElementById(STYLE_ID)?.remove();
    this.env.doc.querySelectorAll(`[${HIDDEN_ATTR}]`).forEach((el) => el.removeAttribute(HIDDEN_ATTR));
  }
}
