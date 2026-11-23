// Every DOM selector the extension relies on, in one place.
// YouTube and Instagram change their markup often. When something stops hiding, fix it here
// (and add a fixture case in test/content.test.ts). Nothing here reads or sends page contents.

export interface HideRule {
  /** CSS selector for the element that marks the unwanted thing. */
  selector: string;
  /** Optional: hide the nearest ancestor matching this instead (e.g. the whole shelf/card). */
  closest?: string;
  /** Why the rule exists, for the next person who has to fix it. */
  note: string;
}

export interface SurfaceConfig {
  surfaceId: "youtube.shorts" | "instagram.reels";
  /** Bare site used in the interstitial copy and for grants. */
  site: string;
  /** Hostnames this config applies to. */
  hosts: RegExp;
  /** A navigation to a path starting with one of these goes to the interstitial. */
  blockedPathPrefixes: string[];
  /**
   * Static CSS hidden with `display:none !important` (may use :has(), Chrome 105+).
   * Fast first paint; the JS rules below are the backstop and what tests exercise.
   */
  css: string[];
  /** Rules applied by the MutationObserver; matched elements get data-steady-hidden. */
  hide: HideRule[];
}

export const YOUTUBE: SurfaceConfig = {
  surfaceId: "youtube.shorts",
  site: "youtube.com",
  hosts: /(^|\.)youtube\.com$/,
  blockedPathPrefixes: ["/shorts"],
  css: [
    "ytd-reel-shelf-renderer", // Shorts shelf in search results / watch sidebar
    "ytd-rich-shelf-renderer[is-shorts]", // Shorts shelf on the home grid
    "ytd-rich-section-renderer:has(ytd-rich-shelf-renderer[is-shorts])", // its wrapper section
    "ytd-guide-entry-renderer:has(a[href^='/shorts'])", // left guide "Shorts" entry
    "ytd-mini-guide-entry-renderer[aria-label='Shorts']", // collapsed guide entry
    "ytm-reel-shelf-renderer", // m.youtube.com shelf
    "ytm-pivot-bar-item-renderer:has(.pivot-shorts)", // m.youtube.com bottom bar tab
    "grid-shelf-view-model:has(ytm-shorts-lockup-view-model)", // 2024+ shelf on search/channel pages
    "yt-tab-shape[tab-title='Shorts']", // channel page "Shorts" tab
  ],
  hide: [
    { selector: "ytd-reel-shelf-renderer", note: "Shorts shelf (search, sidebar)" },
    { selector: "ytd-rich-shelf-renderer[is-shorts]", closest: "ytd-rich-section-renderer", note: "Home Shorts shelf" },
    { selector: "ytm-reel-shelf-renderer", note: "Mobile web Shorts shelf" },
    { selector: "ytm-shorts-lockup-view-model", closest: "grid-shelf-view-model", note: "New Shorts shelf layout" },
    {
      selector: "a[href^='/shorts']",
      closest:
        "ytd-guide-entry-renderer, ytd-mini-guide-entry-renderer, ytd-rich-item-renderer, ytd-video-renderer, ytd-grid-video-renderer, ytm-pivot-bar-item-renderer",
      note: "Guide entry and single videos that open in the Shorts player",
    },
    { selector: "yt-tab-shape[tab-title='Shorts']", note: "Channel page Shorts tab" },
  ],
};

export const INSTAGRAM: SurfaceConfig = {
  surfaceId: "instagram.reels",
  site: "instagram.com",
  hosts: /(^|\.)instagram\.com$/,
  blockedPathPrefixes: ["/reels", "/reel/"],
  css: [
    "a[href='/reels/']", // left nav / bottom bar "Reels" entry
    "a[href^='/reels/']", // links into the Reels feed
    "a[href*='/reels/'][role='tab']", // "Reels" tab on profile pages
  ],
  hide: [
    {
      selector: "a[href='/reels/'], a[href^='/reels/']",
      closest: "div[role='none'], span[role='none'], li",
      note: "Nav entry for the Reels feed (desktop sidebar and mobile bottom bar)",
    },
    { selector: "a[href*='/reels/'][role='tab']", note: "Profile Reels tab" },
  ],
};

/** Merge optional overrides from GET /v1/detectors: `{ web: { "<surfaceId>": { css: [], hide: [] } } }`. */
export function withOverrides(base: SurfaceConfig, detectors: unknown): SurfaceConfig {
  const web = (detectors as { web?: Record<string, { css?: unknown; hide?: unknown }> } | null)?.web;
  const o = web?.[base.surfaceId];
  if (!o) return base;
  const css = Array.isArray(o.css) ? o.css.filter((s): s is string => typeof s === "string") : [];
  const hide = Array.isArray(o.hide)
    ? o.hide.filter(
        (h): h is HideRule => !!h && typeof (h as HideRule).selector === "string",
      )
    : [];
  return { ...base, css: [...base.css, ...css], hide: [...base.hide, ...hide] };
}
