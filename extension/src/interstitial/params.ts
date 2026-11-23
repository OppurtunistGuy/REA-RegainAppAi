// Parsing the interstitial URL: interstitial.html?surface=<id>&site=<domain>&url=<original URL>
// `url` is always last. The declarativeNetRequest redirect puts the raw URL there (it may contain
// & and #), content scripts put it URI-encoded, so take everything after "&url=" from the full href.

export interface InterstitialParams {
  surface: string;
  site: string;
  url: string | null;
}

export function parseInterstitialHref(href: string): InterstitialParams {
  const marker = "&url=";
  const i = href.indexOf(marker);
  const head = i >= 0 ? href.slice(0, i) : href;
  let url: string | null = i >= 0 ? href.slice(i + marker.length) : null;
  if (url !== null && /^https?%3A/i.test(url)) {
    try {
      url = decodeURIComponent(url);
    } catch {
      url = null;
    }
  }
  const q = new URLSearchParams(head.includes("?") ? head.slice(head.indexOf("?") + 1).split("#")[0] : "");
  const site = q.get("site") ?? "";
  return { surface: q.get("surface") ?? (site ? `site:${site}` : ""), site, url: url || null };
}

/** Only send the user back to an http(s) URL on the blocked site; otherwise to the site's home page. */
export function safeReturnUrl(url: string | null, site: string): string | null {
  if (url) {
    try {
      const u = new URL(url);
      const host = u.hostname.toLowerCase();
      if ((u.protocol === "https:" || u.protocol === "http:") && (host === site || host.endsWith(`.${site}`))) {
        return u.href;
      }
    } catch {
      // fall through
    }
  }
  return /^[a-z0-9.-]+\.[a-z]{2,}$/.test(site) ? `https://${site}/` : null;
}

const SURFACE_NAMES: Record<string, string> = {
  "youtube.shorts": "YouTube Shorts",
  "instagram.reels": "Instagram Reels",
  "facebook.reels": "Facebook Reels",
  "snapchat.spotlight": "Snapchat Spotlight",
};

export function surfaceName(surface: string, site: string): string {
  return SURFACE_NAMES[surface] ?? (surface.startsWith("site:") ? surface.slice(5) : site || "this site");
}
