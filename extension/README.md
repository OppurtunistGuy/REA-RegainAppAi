# Steady for Chrome (Phase 10)

A Manifest V3 extension that closes the "I'll just use my laptop" loophole. During scheduled study
blocks it:

- hides YouTube Shorts and Instagram Reels entry points (content scripts, CSS + MutationObserver),
- sends `/shorts/*`, `/reels/` and `/reel/*` to a breath interstitial, including SPA navigations,
- pauses every domain in `plan.siteBlocks` and every `site:<domain>` surface with
  `declarativeNetRequest` dynamic rules (main frame only),
- shows the same intercept as the phone: a 5-second breath, then **Close tab** (primary, available at once),
  **60-second swap** (placeholder card for now; flashcards live in the phone app) and
  **Unlock for N min** (waits `unlockPolicy.waitSeconds`, needs a reason of 8+ non-space characters).

Schedule logic is a TypeScript port of `RulesEngine` (`src/rules.ts`) and passes every case in
`../shared/schema/test-vectors.json`.

## Build, test, load

```bash
cd extension
npm install
npm run typecheck   # tsc --noEmit, strict
npm test            # vitest run (rules vectors, DNR rules, content hiding on jsdom fixtures, interstitial flow)
npm run build       # -> dist/, load it via chrome://extensions -> Developer mode -> Load unpacked
npm run e2e         # builds, then drives the real extension in headless Chromium via Playwright
```

`npm run e2e` uses the preinstalled Playwright (never downloads browsers) with the `chromium` channel
in `--headless=new` mode, because the default headless shell cannot load extensions. A local server
stands in for the API, `www.youtube.com`, `www.instagram.com` and `blocked.test` through
`--host-resolver-rules`. If Chromium cannot load extensions in your environment the script prints
`SKIPPED` and exits 0. Override the channel with `STEADY_E2E_CHANNEL`.

Then open the extension's Options page, set the API base URL (default `http://localhost:8787`),
save, and sign in with the OTP code (the dev backend uses `000000` under `NODE_ENV=test`).

## How it works

| File | Role |
|------|------|
| `src/rules.ts`, `src/tz.ts` | RulesEngine port (`state`, `nextBlock`, `isBlocked`, `blocksOn`) + tiny `Intl` time-zone helper |
| `src/unlock.ts` | UnlockFlow port (`step`, `secondsLeft`, `selfGrant`) |
| `src/policy.ts` | Pure: status computation, domain normalising, DNR rule generation |
| `src/background.ts` | Service worker: sync every 15 min + on startup, recompute every minute and at exact transitions, apply DNR rules |
| `src/content/selectors.ts` | **All** YouTube/Instagram selectors, with comments. Fix breakages here |
| `src/content/guard.ts` | Hiding/redirect engine shared by `youtube.ts` and `instagram.ts` |
| `src/interstitial/*` | Breath page (`controller.ts` holds the logic, `interstitial.ts` wires chrome APIs) |
| `src/ui/*` | Popup and Options pages |
| `static/` | Manifest, HTML, CSS (tokens copied from the prototype, light + dark) |

State lives in `chrome.storage.local`: `settings` (API base, token), `plan` (last plan, used offline),
`detectors`, `grants` (local unlock grants) and `status` (what the worker publishes for the content
scripts and popup). If the server is unreachable or the token is revoked, the cached plan keeps working.

**Redirect vs. block.** Redirecting a request with DNR needs host access to that site. YouTube and
Instagram are in `host_permissions`. For other `siteBlocks`, Options asks for access when you press
Save (`optional_host_permissions: ["<all_urls>"]`, requested per domain). Without it those sites are
still blocked, just with Chrome's error page instead of the breath page.

**Selectors from the server.** `GET /v1/detectors` is cached. If it ever carries a
`web: { "<surfaceId>": { css: [...], hide: [{selector, closest?, note}] } }` block, the content
scripts add those selectors, so web fixes can ship without an extension update.

## Privacy

- The extension makes exactly two kinds of authenticated requests: `GET /v1/plan` and `GET /v1/detectors`.
  The Options page additionally calls the two OTP sign-in endpoints with the contact and code you type.
- No page URLs, page contents, unlock reasons or browsing history are sent anywhere. Unlock reasons
  are not even stored. Content scripts only look at element tags/attributes and the URL path.
- Extension pages load no web fonts or other third-party resources (the font stacks fall back to system faces).

## Deviations and open points

- **Key holder.** The contract says unlocks with a key holder (`notifyKeyHolder`) need approval
  through the backend. The extension only GETs, so it **self-grants** after the wait + reason, as the
  Phase 10 brief asks, and tells the user the key holder can't be asked from the browser yet. Phase 11/12
  can switch this to `POST /v1/unlock-requests` if we decide the browser may post.
- **Non-strict schedules** ("never block the Intercept exits"): the unlock has no wait and no reason.
- **`end == start`** is treated as a 24-hour block starting that day (the vectors don't cover it).
  Phase 2 should agree; add a vector if not.
- **`block_app` mode** on `youtube.shorts`/`instagram.reels` behaves like `hide_section` on the web. Use
  `siteBlocks` or `site:<domain>` to block a whole site.
- Time zone is the browser's (`Intl.DateTimeFormat().resolvedOptions().timeZone`); the Plan has no zone field.
- Unlock grants are local to this browser and are not synced to the phone.
