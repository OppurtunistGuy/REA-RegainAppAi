# Steady for Chrome (Phase 10, updated in Phase 12)

A Manifest V3 extension that closes the "I'll just use my laptop" loophole. During scheduled study
blocks it:

- hides YouTube Shorts and Instagram Reels entry points (content scripts, CSS + MutationObserver),
- sends `/shorts/*`, `/reels/` and `/reel/*` to a breath interstitial, including SPA navigations,
- pauses every domain in `plan.siteBlocks` and every `site:<domain>` surface with
  `declarativeNetRequest` dynamic rules (main frame only),
- shows the same intercept as the phone: a 5-second breath, then **Close tab** (primary, available at once),
  **60-second swap** (placeholder card for now; flashcards live in the phone app) and
  **Unlock for N min** (waits `unlockPolicy.waitSeconds`, needs a reason of 8+ non-space characters).
  With a key holder and `notifyKeyHolder`, the unlock asks them through the backend and applies
  their approval; without one it is granted locally.

Schedule logic is a TypeScript port of `RulesEngine` (`src/rules.ts`) and passes every case in
`../shared/schema/test-vectors.json`.

## Build, test, load

```bash
cd extension
npm install
npm run typecheck   # tsc --noEmit, strict
npm test            # vitest run (rules vectors, DNR rules, content hiding on jsdom fixtures, interstitial flow)
npm run build       # release build -> dist/ (https API only, no localhost hosts)
npm run build:dev   # dev build -> dist/ (also http://localhost and http://127.0.0.1, http API allowed)
npm run e2e         # build:dev, then drives the real extension in headless Chromium via Playwright
```

`npm run e2e` uses the preinstalled Playwright (never downloads browsers) with the `chromium` channel
in `--headless=new` mode, because the default headless shell cannot load extensions. A local server
stands in for the API, `www.youtube.com`, `www.instagram.com` and `blocked.test` through
`--host-resolver-rules`. If Chromium cannot load extensions in your environment the script prints
`SKIPPED` and exits 0. Override the channel with `STEADY_E2E_CHANNEL`.

Load `dist/` via chrome://extensions -> Developer mode -> Load unpacked. Open the Options page,
check the API base URL, save, and sign in with the OTP code (the dev backend uses `000000` under
`NODE_ENV=test`).

**Release vs dev builds.** `npm run build` reads `STEADY_API_BASE` (default `https://api.steady.app`),
refuses `http://` and localhost, and writes that one API host into `host_permissions` next to
YouTube and Instagram (`scripts/manifest.mjs`; `static/manifest.json` is the template and has no API
host). The default API base and an `https`-only check on the Options page are compiled in with esbuild
`define` (`src/buildinfo.ts`). `npm run build:dev` defaults to `http://localhost:8787`, adds
`http://localhost/*` and `http://127.0.0.1/*`, accepts http API URLs and names the extension
"Steady (dev)".

**Dependencies.** vitest 4.1.12 + jsdom 31; `npm audit` (dev and prod) is clean. npm 10 crashes with
"Cannot read properties of null (reading 'edgesOut')" when it builds the tree for vitest 4 *without*
a lockfile (an optional peer cycle through `@vitejs/devtools`), so `.npmrc` sets
`legacy-peer-deps=true`. `npm ci` and `npm install` with the committed lockfile work either way.

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
`detectors`, `grants` (unlock grants), `pendingUnlock` (key-holder request awaiting an answer), `authNotice` (set after a 401) and `status` (what the worker publishes for the content
scripts and popup). If the server is unreachable or the token is revoked, the cached plan keeps working.

**Redirect vs. block.** Redirecting a request with DNR needs host access to that site. YouTube and
Instagram are in `host_permissions`. For other `siteBlocks`, Options asks for access when you press
Save (`optional_host_permissions: ["<all_urls>"]`, requested per domain). Without it those sites are
still blocked, just with Chrome's error page instead of the breath page.

**Selectors from the server.** `GET /v1/detectors` is cached. If it ever carries a
`web: { "<surfaceId>": { css: [...], hide: [{selector, closest?, note}] } }` block, the content
scripts add those selectors, so web fixes can ship without an extension update.

## Privacy

- With the bearer token the extension makes only these requests: `GET /v1/plan`, `GET /v1/detectors`,
  and, when you unlock while your plan has a key holder with `notifyKeyHolder`,
  `POST /v1/unlock-requests` with exactly `{surfaceId, reason}` (the surface id such as
  `youtube.shorts` or `site:instagram.com`, and the reason you typed) followed by
  `GET /v1/unlock-requests/{id}` every 10 s for up to 15 minutes. Signing out sends
  `POST /v1/auth/logout` (no body). The Options page also calls the two OTP sign-in endpoints with the
  contact and code you type.
- No page URLs, page contents or browsing history are sent anywhere. Without a key holder the unlock
  reason never leaves the browser and is not stored. Content scripts only look at element
  tags/attributes and the URL path.
- Extension pages load no web fonts or other third-party resources (the font stacks fall back to system faces).

## Deviations and open points

- **Key holder.** After the wait + reason, a plan with a key holder and `notifyKeyHolder` sends
  `POST /v1/unlock-requests`; the interstitial shows "Asked <name>" and polls every 10 s for up to
  15 min. Approved → a grant until the server's `until`, `approvedBy` = key holder name; denied or no
  answer → no grant. The pending request is kept in `chrome.storage.local` (`pendingUnlock`), so
  reopening the page for the same surface keeps waiting. Polling only runs while the page is open.
  There is no self-grant while a key holder must answer, including when signed out (the button is
  disabled and the page says to sign in). Local grants only when: no key holder, `notifyKeyHolder`
  off, `relation: "self"` (nobody to ask), a non-strict schedule, or the server says 409
  `no_keyholder` (no link exists on the server, so the plan's key holder is stale).
- **Expired sign-in.** A 401 from the API (e.g. `token_expired` after 90 idle days) clears the token,
  stores an `authNotice`, and the popup shows "Sign in again" (opens Options, which shows the notice
  above the sign-in form). The cached plan keeps enforcing blocks meanwhile.
- **Non-strict schedules** ("never block the Intercept exits"): the unlock has no wait, no reason and
  no key-holder step.
- **`end == start`** is treated as a 24-hour block starting that day (the vectors don't cover it).
  Phase 2 should agree; add a vector if not.
- **`block_app` mode** on `youtube.shorts`/`instagram.reels` behaves like `hide_section` on the web. Use
  `siteBlocks` or `site:<domain>` to block a whole site.
- Time zone is the browser's (`Intl.DateTimeFormat().resolvedOptions().timeZone`); the Plan has no zone field.
- Unlock grants are local to this browser and are not synced to the phone.
