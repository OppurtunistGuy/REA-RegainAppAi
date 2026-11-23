# QA test plan (manual)

Automated tests already cover the pure logic: `./gradlew build` (rules, detect, learning,
enforcement decider, view models), `backend: npm test`, `extension: npm test` and `npm run e2e`.
This plan covers what only a real device, a real Play account or a real browser can show. Run it on
the **release build** (minified, `isMinifyEnabled = true`), signed with the upload key and installed
from the Play internal-testing track, unless a case says otherwise.

Record each run in a sheet with these columns: case id, device, Android version, target-app
version, build `versionCode`, result (pass, fail or note), notes, tester, date.

## 1. Device matrix

minSdk is 26 (Android 8) and targetSdk is 35 (see B11: Android 16 / API 36 may be required).
Cover every row at least once per release candidate. ★ = must-pass before internal testing.

| # | Device (or equivalent) | Android / skin | Why | Priority |
|---|------------------------|----------------|-----|----------|
| D1 | Pixel 8 or 9 | Android 16 (API 36) | Reference; newest restrictions | ★ |
| D2 | Pixel 6 or 7 | Android 15 (API 35) | Restricted settings for sideloaded apps; target API | ★ |
| D3 | Samsung Galaxy A15/A35/M-series | One UI 6–7 (Android 14–15) | Top seller in India; "Sleeping apps" / "Deep sleeping" kills services | ★ |
| D4 | Xiaomi / Redmi / POCO | HyperOS or MIUI 14 (Android 13–14) | Autostart off by default; battery saver kills accessibility services | ★ |
| D5 | Vivo or Oppo / Realme (ColorOS / Funtouch) | Android 13–14 | Aggressive background limits; common in India | ★ |
| D6 | OnePlus Nord | OxygenOS (Android 14) | Battery optimisation, "App auto-launch" | |
| D7 | Samsung (older) | Android 12 (API 31–32) | Background FGS-start limits introduced | |
| D8 | Any | Android 11 (API 30) | Package visibility (`<queries>`) | |
| D9 | Any low-RAM (3–4 GB) | Android 10 (API 29) | Low memory kills; older accessibility behaviour | ★ |
| D10 | Emulator | Android 8.0 (API 26) | minSdk smoke test only (launch, Setup, Today) | |

**Battery killers.** On D3–D6, run each enforcement case twice: once with default battery settings
and once after setting Steady to "Unrestricted" (or "No restrictions" plus Autostart on).
Document the exact settings path for each OEM. Use dontkillmyapp.com as a starting point. These
paths become the in-app help text later.

**Target app versions.** For each package, test the current Play Store version on each device. On
D1, also test the previous major version (APKMirror or a second device with auto-update off) and
the beta channel if available. Record versions with:

```sh
adb shell dumpsys package com.instagram.android | grep versionName
adb shell dumpsys package com.google.android.youtube | grep versionName
adb shell dumpsys package com.snapchat.android | grep versionName
adb shell dumpsys package com.facebook.katana | grep versionName
```

Re-run section 3 for any target-app update during the beta.

## 2. Core enforcement cases

Setup for each: plan with all four sections in `hide_section`, BGMI (`app:com.pubg.imobile`) as
`block_app`, a strict schedule that is active now, key holder "Self". Accessibility on.

| Id | Steps | Expected |
|----|-------|----------|
| E-01 ★ | Open Instagram → Reels tab | Within about 0.5 s: Back once, then the intercept overlay (breath, three exits). An `intercept_events` row is added. Home feed and DMs keep working |
| E-02 ★ | Instagram: open a Reel from a DM | Leaves the Reel (Back), overlay shown, still in DMs |
| E-03 ★ | YouTube → Shorts tab; YouTube → a Short from search; YouTube → a normal lecture video | Shorts are caught both ways; the normal video plays uninterrupted |
| E-04 | Snapchat → Spotlight | Caught |
| E-05 | Facebook → Reels | Caught |
| E-06 ★ | Open BGMI (or any `app:` blocked app) | Home, then the overlay with whole-app copy |
| E-07 ★ | Overlay → "Close Instagram" | Goes Home |
| E-08 ★ | Overlay → "60-second swap" | Steady opens on Swap (needs the 12a `EXTRA_ROUTE` wiring) |
| E-09 ★ | Overlay → "Just messages" | Overlay closes; stays in the app; Reels caught again when reopened |
| E-10 | Re-enter Reels 10 times quickly | No loop or flicker; at most one action per 1.5 s per package; overlay never stacks |
| E-11 ★ | Outside any block, open Reels | Nothing happens. **Privacy check:** add a temporary log or use the debugger to confirm the tree is not walked (`anyBlocked` false) |
| E-12 | Non-strict ("flexible") schedule | Intercept exits are not gated (once 12a adds the `strict` flag to the overlay) |
| E-13 ★ | Block starts while the phone is idle (screen off 30 min before start) | `FocusService` notification "<label> · N min left" appears within a few minutes of start; enforcement works at once even if the notification is late |
| E-14 ★ | Block ends | Notification disappears; Reels work again |
| E-15 | Reboot during a block (needs the 12a manifest receivers) | After unlock, the block is enforced again and the notification comes back |
| E-16 | Android 13+: notifications permission denied | The FGS still runs; note that `POST_NOTIFICATIONS` is never requested at runtime (12a) |
| E-17 | Detector health: break detection (an old Instagram version with different ids) for 20+ events | Health turns unhealthy, and Instagram falls back to Home (BLOCK_APP) instead of failing silently |
| E-18 | Split screen: Instagram Reels plus Steady or Chrome | Record the result (only the active window is checked; expected miss when the other pane is focused) |
| E-19 | Picture-in-picture YouTube Short | Record the result |
| E-20 | TalkBack on, then open Reels | Overlay is announced; exits are reachable (see §7) |

## 3. Detector verification and update procedure

Use this when a target app updates or E-01 to E-05 fail.

1. **Use a test account** with no real contacts. Dumps contain on-screen text (names, messages).
   Never commit raw dumps, and delete them after use.
2. Open the target app on the screen to identify (for example the Reels tab, playing).
3. Dump the node tree:
   ```sh
   adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ig-reels.xml
   ```
   If it fails with "could not get idle state" (this is common on video screens), pause the video,
   or use Android Studio › Layout Inspector, or a debug-only node dump. Suggestion for a later phase:
   a debug build menu that logs `viewIdResourceName`, `className`, `contentDescription` and
   `isSelected` for the active window, with text redacted.
4. Find a stable marker for the section: prefer `resource-id` (match with `viewIdEndsWith`, for
   example `:id/clips_viewer_view_pager`). Next best is a **selected** tab with a
   `content-desc` (`descContains` + `selected: true`). Avoid raw `text` because it changes with locale.
   Also find a stable **anchor** that is present on every screen of the app (the bottom tab bar).
5. Check the marker is **absent** on the screens that must keep working: home feed, DMs, Stories,
   search, profile, a normal video.
6. Edit `shared/detectors/detectors.v1.json`. Add the matcher to `anyOf` (keep the old matchers
   while old app versions are in use), and bump `"version"`.
7. Add a fixture and test in `android/core/detect` (a tree that should match and one that should
   not), then run `cd android && ./gradlew :core:detect:test`.
8. Repeat the matching check for the web selectors in `extension/src/content/selectors.ts` if the
   website changed. Run `cd extension && npm test && npm run e2e`.
9. Ship it by deploying the backend, so `GET /v1/detectors` serves the new file
   (`DETECTORS_PATH`). **Gap:** the app does not apply remote detector configs yet. It uses the
   bundled asset (`docs/INTEGRATION_TODO.md`, Phase 11 list). Until 12a wires
   `SyncApi.detectors()`, a fix needs an app release. That misses the BUILD_PLAN goal of a fix in
   under 24 hours without a release.
10. Re-run E-01 to E-05 and E-17 on D1 and D3.

## 4. Strict-mode bypass attempts

Run these during an active **strict** block. "Expected (current code)" is what the code does
today, not what we would like. A ⚠ marks a known gap to track, not a test failure. The Play policy
forbids us from blocking several of these (disabling the service, uninstalling), so the answer
there is detection and honesty, not prevention.

| Id | Attempt | Expected (current code) | Notes |
|----|---------|--------------------------|-------|
| S-01 | Settings › Accessibility › turn Steady focus helper off | Enforcement stops at once. No warning, no key-holder notice. ⚠ | Allowed by policy and must stay possible. Later: show "Focus helper is off" on Today and Focus, and optionally notify the key holder ("helper turned off during a block"), which is disclosed data |
| S-02 | Settings › Apps › Steady › Force stop | Service process killed. Record whether it rebinds by itself (OEM-dependent; often not until reboot or toggling) ⚠ | Same mitigation as S-01 |
| S-03 | Reboot into safe mode | No third-party accessibility services run, so no enforcement | Accepted limit; document it |
| S-04 | Change the clock manually (Settings › Date & time, auto off) to after the block ends | `RulesEngine` uses the device clock (`container.clock`), so the block ends early. `BootReceiver` resyncs on TIME_SET (once registered) but trusts the new time ⚠ | Later: compare against `SystemClock.elapsedRealtime` since block start and against server time |
| S-05 | Change time zone so local time leaves the block | Same as S-04. `container.rules` keeps the old `ZoneId` until restart (`INTEGRATION_TODO`), so the result is inconsistent; record it ⚠ | |
| S-06 | Uninstall Steady | Uninstall succeeds. Local data is gone. Server data stays (if signed in). Key holder not told | Must stay possible (policy) |
| S-07 | Settings › Apps › Steady › Clear storage | Plan wiped; app returns to Setup; no enforcement | Same as S-06 |
| S-08 | Open Steady › Settings › Edit my plan during a strict block, and remove Reels or the schedule | **Allowed: the edit saves and the block ends.** ⚠ The biggest gap; strict mode is not strict against itself | Product fix: lock edits that loosen the plan during a strict block, or apply them after the block ends |
| S-09 | Emergency unlock, self (no key holder) | 30 s wait, then a reason of at least 8 non-space characters, then a 15-minute grant; enforcement pauses for that surface only | `UnlockFlow` |
| S-10 | Emergency unlock with a key holder while signed out | Shows the NotSignedIn path. Check the UI explains it (12a: observe `outcomes`) | |
| S-11 | Instagram or YouTube in Chrome on the phone (m.instagram.com/reels) | Not caught (no web detector on Android) ⚠ | Later: domain fallback |
| S-12 | Instagram Lite, Facebook Lite, ReVanced or other YouTube clients | Not caught (package not in detectors) ⚠ | Consider adding packages |
| S-13 | Dual apps / Dual Messenger / second user / work profile copy of Instagram | Not caught in the other profile ⚠ | |
| S-14 | Split screen or a floating window with Reels in the inactive pane | See E-18 | |
| S-15 | Battery saver or "Deep sleeping" puts Steady to sleep (D3–D6, default settings) | Record whether the accessibility service survives 2 h with the screen off | Feeds the OEM help text |
| S-16 | Turn off the Focus notification channel | FGS continues silently; enforcement unaffected | |
| S-17 | Laptop: disable the extension, use Incognito, use another browser | Not prevented; the extension cannot detect this ⚠ | Document it in help |

## 5. Key holder flow

Two devices (A = owner, B = key holder), both signed in to staging, different contacts.

| Id | Steps | Expected |
|----|-------|----------|
| K-01 ★ | A: Key holder › Create invite code → share | 6-character code (A–Z 2–9, no I/O/0/1), valid 24 h, works once |
| K-02 ★ | B: Key holder › I hold a key → enter code | "You now hold A's key." A shows "Your key holder: B's name" |
| K-03 | B enters own code; reuses a code; uses an expired code | Error texts `acct_err_self_invite`, `acct_err_invite_gone` |
| K-04 ★ | A: in a strict block → Emergency unlock → wait → reason "lecture link on YouTube" → Ask B | A shows "Asked B…" |
| K-05 ★ | B: open Key holder → Refresh | Request shows A's name, the reason in quotes, "For: YouTube Shorts". **No push:** B sees it only after opening the app ⚠ (copy says "gets told") |
| K-06 ★ | B: Approve 15 min | Within one poll interval, A gets "Unlocked until hh:mm"; only that surface is unblocked |
| K-07 | B: Not now | A sees denied; block continues |
| K-08 | No decision for 15 min | A sees timed out |
| K-09 | Free tier: a second key holder accepts A's invite | 409 `keyholder_limit` (12b contract) |
| K-10 | A has no name set (signed in without a name) | B sees the email local part or "•••1234" (B16). Check this is acceptable |
| K-11 | Unlink (once `DELETE /v1/keyholder/links/{id}` and its UI ship) | Both sides lose the link; pending requests no longer visible to B |
| K-12 | Privacy check: inspect B's API responses (`GET /v1/keyholder/requests`) with a proxy | Only id, ownerName, reason, surfaceId, createdAt. No sessions, no screen data |

## 6. Billing (Play license testers)

Setup: add tester Gmail accounts under Play Console › Settings › License testing (test card and
UPI test instruments), and also as internal-testing testers. Create subscriptions
`steady_plus_monthly` and `steady_plus_yearly` (names from `PlusProducts` and the backend
contract), with base plans and INR and USD prices. Tester renewals are accelerated (monthly renews
every 5 minutes, at most 6 renewals).

| Id | Steps | Expected |
|----|-------|----------|
| P-01 ★ | Paywall shows prices | Play-formatted prices, not the ₹149 / ₹999 fallback |
| P-02 ★ | Subscribe monthly with "Test card, always approves" | Plus active, "You have Steady Plus." Purchase **acknowledged** within seconds (check Play Console › Order management; unacknowledged purchases refund after 3 days) |
| P-03 | "Test card, always declines" | Error message, still Free |
| P-04 | Slow test card (pending) | Pending purchase does not grant Plus until it completes |
| P-05 ★ | Settings › Manage or cancel subscription | Opens the Play subscription page for that SKU; cancel works in one tap; Plus stays until the period ends |
| P-06 | Let renewals run out (about 30 min) | Plus ends; app returns to Free on the next refresh, with the extra schedules handled gracefully |
| P-07 | Restore purchases on a second device with the same Google account | "Steady Plus is active." |
| P-08 | Refund or revoke from Play Console | Plus removed on next refresh |
| P-09 ★ | Server verification (once B9 ships): `POST /v1/billing/verify` with a real tester token in staging | `{tier:"plus"}`; production without credentials → 503 `billing_not_configured` |
| P-10 | Entitlement offline | Plus purchase still recognised from the Play cache; Free when signed out with no purchase |
| P-11 | Upgrade monthly → yearly | Proration mode correct; one active subscription |

## 7. Accessibility of Steady itself

| Id | Check | Expected |
|----|-------|----------|
| X-01 ★ | TalkBack: complete Setup → Today → Focus → Unlock → Recap with swipe navigation only | Every control has a label; headings announced (`semantics { heading() }` on disclosure cards); focus order is logical; the ring announces "About N left of label" |
| X-02 ★ | TalkBack on the intercept overlay | Overlay content is reachable and announced; the breath countdown is announced but not every second (`intercept_breath_seconds_left` plurals); the exits work with double-tap |
| X-03 ★ | Font scale 200% (Settings › Display › Font size, maximum, plus display size large) | No clipped or overlapping text on Setup, Today, Focus, Intercept, Paywall, Settings; screens scroll; buttons grow (≥ 48 dp targets) |
| X-04 | Remove animations on (Accessibility › Remove animations) | Breath and ring animations respect it (`InterceptContent` has a "Remove animations" path) |
| X-05 | Dark theme, high-contrast text | Contrast ≥ 4.5:1 for body text |
| X-06 | Switch Access or a keyboard | All exits reachable |
| X-07 | RTL pseudo-locale (Developer options) | Layout mirrors without breaking (`supportsRtl=true`) |
| X-08 | Accessibility Scanner on every screen | No critical issues |
| X-09 | TalkBack plus Steady's own service together | Both work; Steady never blocks TalkBack or the Settings app |

## 8. Chrome extension

Build the store ZIP (`npm run build`, zip `dist/`), then load it unpacked in Chrome stable, beta
and 116 (`minimum_chrome_version`). Also test Edge and Brave (they are Chromium-based and should
work, but treat them as unsupported).

| Id | Steps | Expected |
|----|-------|----------|
| C-01 ★ | Options: sign in with the phone app's contact and OTP | "Signed in. Your plan syncs every 15 minutes." Plan visible in the popup |
| C-02 ★ | Active block: youtube.com home | Shorts shelf and Shorts tab hidden; regular videos play |
| C-03 ★ | Active block: open youtube.com/shorts/<id> directly, and through in-app (SPA) navigation | Breath interstitial |
| C-04 ★ | Active block: instagram.com/reels/ and /reel/<id> | Interstitial |
| C-05 ★ | A listed site (for example x.com) with optional host access granted | Breath page; without access, Chrome's block page |
| C-06 | Interstitial: Close tab / swap / unlock (wait plus reason of 8+ characters) | Matches the phone; grant is local to this browser |
| C-07 | Block starts while Chrome is open idle | Rules applied within a minute (alarms) |
| C-08 | Offline / token revoked | The cached plan keeps working |
| C-09 | Plan edited on the phone | The extension picks it up within 15 minutes |
| C-10 | Privacy check: DevTools › Network on the service worker | Only `GET /v1/plan`, `GET /v1/detectors` (plus OTP at sign-in) to the API host; nothing sent from content scripts |
| C-11 | Key holder set | The extension self-grants and says the key holder can't be asked from the browser (known deviation) |
| C-12 | Store build manifest | No localhost hosts; the real API host (B14) |

## 9. Backend and privacy smoke tests (staging)

| Id | Check | Expected |
|----|-------|----------|
| B-01 ★ | `POST /v1/sessions` with an extra field (for example `"apps":[...]`) | 400 `invalid_body`; the value is not echoed |
| B-02 ★ | HTTP (not HTTPS) request to the production host | Refused or redirected; HSTS set at the proxy |
| B-03 ★ | `DELETE /v1/me` (once shipped), then check every table for the userId and contact | Nothing left (including `users` and `otp_codes`) |
| B-04 ★ | Server logs during sign-in | **No OTP codes, no full contacts** (B7) |
| B-05 | Token unused for more than 90 days (clock-shifted test env) | 401 `token_expired` |
| B-06 | Rate limits | OTP start: 6th try in an hour → 429 |
