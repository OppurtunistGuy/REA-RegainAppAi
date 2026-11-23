// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { HIDDEN_ATTR, STYLE_ID, SurfaceGuard, buildCss, pathBlocked, type GuardEnv } from "../src/content/guard.js";
import { isSurfaceActive } from "../src/content/run.js";
import { INSTAGRAM, YOUTUBE, withOverrides } from "../src/content/selectors.js";
import type { PublishedStatus } from "../src/types.js";

const YT_FIXTURE = `
<ytd-app>
  <ytd-guide-renderer>
    <ytd-guide-entry-renderer id="g-home"><a href="/">Home</a></ytd-guide-entry-renderer>
    <ytd-guide-entry-renderer id="g-shorts"><a href="/shorts/" title="Shorts">Shorts</a></ytd-guide-entry-renderer>
  </ytd-guide-renderer>
  <ytd-mini-guide-entry-renderer id="mini-shorts" aria-label="Shorts"><a href="/shorts/">Shorts</a></ytd-mini-guide-entry-renderer>
  <ytd-rich-grid-renderer>
    <ytd-rich-item-renderer id="video"><a href="/watch?v=abc">A lecture</a></ytd-rich-item-renderer>
    <ytd-rich-section-renderer id="shorts-section">
      <ytd-rich-shelf-renderer is-shorts><span>Shorts</span></ytd-rich-shelf-renderer>
    </ytd-rich-section-renderer>
    <ytd-rich-item-renderer id="short-item"><a href="/shorts/xyz">A short</a></ytd-rich-item-renderer>
  </ytd-rich-grid-renderer>
  <ytd-search><ytd-reel-shelf-renderer id="search-shelf"></ytd-reel-shelf-renderer></ytd-search>
</ytd-app>`;

const IG_FIXTURE = `
<nav>
  <div><span role="none"><a id="home" href="/">Home</a></span></div>
  <div><span role="none" id="reels-wrap"><a id="reels" href="/reels/">Reels</a></span></div>
  <div><span role="none"><a id="dm" href="/direct/inbox/">Messages</a></span></div>
</nav>
<main><a id="profile-reels" role="tab" href="/someone/reels/">Reels</a></main>`;

function makeEnv(href: string): GuardEnv & { redirects: string[]; navigate: (h?: string) => void; setHref: (h: string) => void } {
  let current = href;
  let listener: ((next?: string) => void) | null = null;
  const redirects: string[] = [];
  return {
    doc: document,
    href: () => current,
    redirect: (u) => redirects.push(u),
    interstitialUrl: (s, site, h) => `chrome-extension://id/interstitial.html?surface=${s}&site=${site}&url=${encodeURIComponent(h)}`,
    onNavigate: (cb) => {
      listener = cb;
      return () => (listener = null);
    },
    redirects,
    navigate: (h) => listener?.(h),
    setHref: (h) => (current = h),
  };
}

const hidden = (id: string): boolean => document.getElementById(id)!.hasAttribute(HIDDEN_ATTR);
const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

describe("pathBlocked", () => {
  it("matches path segments, not substrings", () => {
    expect(pathBlocked("/shorts/abc", ["/shorts"])).toBe(true);
    expect(pathBlocked("/shorts", ["/shorts"])).toBe(true);
    expect(pathBlocked("/shortstack", ["/shorts"])).toBe(false);
    expect(pathBlocked("/reel/abc/", INSTAGRAM.blockedPathPrefixes)).toBe(true);
    expect(pathBlocked("/reels/", INSTAGRAM.blockedPathPrefixes)).toBe(true);
    expect(pathBlocked("/reelsy", INSTAGRAM.blockedPathPrefixes)).toBe(false);
    expect(pathBlocked("/direct/inbox/", INSTAGRAM.blockedPathPrefixes)).toBe(false);
  });
});

describe("YouTube guard", () => {
  beforeEach(() => {
    document.head.innerHTML = "";
    document.body.innerHTML = YT_FIXTURE;
  });
  afterEach(() => vi.restoreAllMocks());

  it("does nothing while inactive", () => {
    const env = makeEnv("https://www.youtube.com/");
    new SurfaceGuard(YOUTUBE, env).setActive(false);
    expect(document.getElementById(STYLE_ID)).toBeNull();
    expect(hidden("search-shelf")).toBe(false);
  });

  it("hides Shorts shelves and entry points, leaves normal videos", () => {
    const env = makeEnv("https://www.youtube.com/");
    const g = new SurfaceGuard(YOUTUBE, env);
    g.setActive(true);
    expect(document.getElementById(STYLE_ID)?.textContent).toContain("ytd-reel-shelf-renderer");
    expect(hidden("search-shelf")).toBe(true);
    expect(hidden("shorts-section")).toBe(true);
    expect(hidden("g-shorts")).toBe(true);
    expect(hidden("mini-shorts")).toBe(true);
    expect(hidden("short-item")).toBe(true);
    expect(hidden("video")).toBe(false);
    expect(hidden("g-home")).toBe(false);
    expect(env.redirects).toEqual([]);
  });

  it("hides shelves added later (MutationObserver)", async () => {
    const g = new SurfaceGuard(YOUTUBE, makeEnv("https://www.youtube.com/"));
    g.setActive(true);
    const late = document.createElement("div");
    late.innerHTML = `<ytd-reel-shelf-renderer id="late"></ytd-reel-shelf-renderer>`;
    document.body.appendChild(late);
    const recycled = document.getElementById("video")!.querySelector("a")!;
    recycled.setAttribute("href", "/shorts/new");
    await flush();
    expect(hidden("late")).toBe(true);
    expect(hidden("video")).toBe(true);
  });

  it("redirects /shorts/ on load and on SPA navigation", async () => {
    const env = makeEnv("https://www.youtube.com/shorts/abc");
    new SurfaceGuard(YOUTUBE, env).setActive(true);
    expect(env.redirects).toHaveLength(1);
    expect(env.redirects[0]).toContain("surface=youtube.shorts");
    expect(env.redirects[0]).toContain(encodeURIComponent("https://www.youtube.com/shorts/abc"));

    const env2 = makeEnv("https://www.youtube.com/");
    new SurfaceGuard(YOUTUBE, env2).setActive(true);
    env2.navigate("https://www.youtube.com/watch?v=1");
    expect(env2.redirects).toEqual([]);
    env2.navigate("https://www.youtube.com/shorts/def"); // e.g. Navigation API destination
    expect(env2.redirects).toHaveLength(1);

    const env3 = makeEnv("https://www.youtube.com/");
    new SurfaceGuard(YOUTUBE, env3).setActive(true);
    env3.setHref("https://www.youtube.com/shorts/ghi"); // pushState without an event; DOM changes
    document.body.appendChild(document.createElement("div"));
    await flush();
    expect(env3.redirects).toHaveLength(1);
  });

  it("deactivating removes the style and unhides", () => {
    const env = makeEnv("https://www.youtube.com/");
    const g = new SurfaceGuard(YOUTUBE, env);
    g.setActive(true);
    g.setActive(false);
    expect(document.getElementById(STYLE_ID)).toBeNull();
    expect(document.querySelectorAll(`[${HIDDEN_ATTR}]`)).toHaveLength(0);
    env.navigate("https://www.youtube.com/shorts/x");
    expect(env.redirects).toEqual([]);
  });
});

describe("Instagram guard", () => {
  beforeEach(() => {
    document.head.innerHTML = "";
    document.body.innerHTML = IG_FIXTURE;
  });

  it("hides the Reels entry, keeps Home and Messages", () => {
    const env = makeEnv("https://www.instagram.com/");
    new SurfaceGuard(INSTAGRAM, env).setActive(true);
    expect(hidden("reels-wrap")).toBe(true);
    expect(hidden("profile-reels")).toBe(true);
    expect(document.getElementById("home")!.closest(`[${HIDDEN_ATTR}]`)).toBeNull();
    expect(document.getElementById("dm")!.closest(`[${HIDDEN_ATTR}]`)).toBeNull();
  });

  it("redirects /reels/ and /reel/<id>, not DMs", () => {
    for (const [href, n] of [
      ["https://www.instagram.com/reels/", 1],
      ["https://www.instagram.com/reel/C0abc/", 1],
      ["https://www.instagram.com/direct/inbox/", 0],
    ] as const) {
      const env = makeEnv(href);
      new SurfaceGuard(INSTAGRAM, env).setActive(true);
      expect(env.redirects).toHaveLength(n);
    }
  });
});

describe("helpers", () => {
  const status = (over: Partial<PublishedStatus>): PublishedStatus => ({
    computedAt: 0,
    active: true,
    scheduleId: "s",
    label: "S",
    strict: true,
    endsAt: 1000,
    blockedSurfaces: ["youtube.shorts"],
    blockedDomains: [],
    next: null,
    signedIn: true,
    lastSyncAt: null,
    lastSyncError: null,
    ...over,
  });
  it("isSurfaceActive", () => {
    expect(isSurfaceActive(null, "youtube.shorts", 0)).toBe(false);
    expect(isSurfaceActive(status({}), "youtube.shorts", 500)).toBe(true);
    expect(isSurfaceActive(status({}), "youtube.shorts", 1000)).toBe(false); // ended
    expect(isSurfaceActive(status({}), "instagram.reels", 500)).toBe(false);
    expect(isSurfaceActive(status({ active: false }), "youtube.shorts", 500)).toBe(false);
  });
  it("buildCss includes the hidden attribute", () => {
    expect(buildCss(YOUTUBE)).toMatch(/\[data-steady-hidden\] \{ display: none !important; \}$/);
  });
  it("withOverrides merges web selectors from /v1/detectors", () => {
    const merged = withOverrides(YOUTUBE, { web: { "youtube.shorts": { css: ["x-new"], hide: [{ selector: "x-new", note: "n" }, 4] } } });
    expect(merged.css).toContain("x-new");
    expect(merged.hide.at(-1)?.selector).toBe("x-new");
    expect(withOverrides(YOUTUBE, { version: 1, detectors: [] })).toBe(YOUTUBE);
  });
});
