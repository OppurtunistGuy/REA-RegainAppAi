# Phase 12 integration checklist (collected from phase reports)

Status marks for Android items are from Phase 12a (✅ done, ⚠️ partly / see note, ➖ not Android).

## From Phase 6 (enforcement)
- ✅ Manifest: add `RECEIVE_BOOT_COMPLETED`, `<receiver .enforcement.BlockAlarmReceiver exported=false>`,
  `<receiver .enforcement.BootReceiver exported=false>` with BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED.
- ✅ MainActivity: read `EXTRA_ROUTE` / `EXTRA_SCHEDULE_ID` via `launchRouteFor(...)` in onCreate and onNewIntent;
  SteadyNavHost needs to accept a pending route. (`DeepLink` → `RouteTable.deepLink`, applied once the NavHost exists.)
- ✅ Call `BlockScheduler.sync(context)` from SteadyApp start and after plan save (SteadyApp collects the plan flow).
- ✅ Link to `Disclosure` route from Setup (after save when Accessibility is off) / Today (banner) / Settings.
- ✅ Delete unused `accessibility_service_description` from strings.xml.
- ✅ InterceptUiState has no `strict` field; consider adding. (`strict = true` default; non-strict: no breath gate, softer copy.)
- ✅ `container.rules` captures ZoneId at creation; rebuild on TIMEZONE_CHANGED. (`ZoneAwareRules`, `DeviceClock`,
  `LocalSessionRepository` reads the zone per query; the service rebuilds its decider when rules change.)

## From Phase 5 (shell)
- ✅ Verify `res/values/font_certs.xml` against Android Studio's generated file. (Identical to
  android/user-interface-samples `DownloadableFonts/.../font_certs.xml`; the compose-samples paths 404.)
- ✅ Free 2-schedule limit enforced in Setup; Phase 11 FreeTier may duplicate it. (Setup uses `FreeTier.scheduleLimit`;
  `Features.FREE_SCHEDULE_LIMIT` removed.)

## From Phase 10 (extension)
- ➖ vitest 3.2.x has audit advisories; upgrade to vitest 4.1.12+ when npm can install it.
- ➖ Extension self-grants unlocks even with a key holder; consider POST /v1/unlock-requests.
- ✅ `end == start` schedules = 24h (rules engine agrees).

## From Phase 9 (backend)
- ➖ Bearer tokens never expire; add expiry/rotation. (Android handles 401 `token_expired`: `AuthGuard` signs out
  and Settings explains.)
- ✅ Missing `DELETE /v1/me` for "delete my synced data". (Android client + confirm dialog done.)

## From Phase 7 (intercept + focus)
- ✅ Overlay (Phase 6) should pass raw schedule label and compute opensToday/opensBaseline like InterceptViewModel
  (BASELINE_DAYS, InterceptCopy.baseline) instead of the placeholder 11. (`InterceptStateBuilder.forOverlay`.)
- ✅ Check intercept double-counting between in-app Intercept screen and the overlay. (The service is the only
  recorder of real opens; the in-app route records only with `Intercept(recordOpen = true)` from Today's debug button.)
- ⚠️ Focus session starts when Focus opens, not at schedule start. Unchanged: the session measures the time the user
  actually spent on the Focus screen; FocusService still covers the whole scheduled block.
- ✅ Pending key-holder requests are shown but polled only by Phase 11. (Focus observes `RemoteUnlockRepository.outcomes`.)

## From Phase 8 (learning UI)
- ✅ Add `CardRepository.delete(ids)` + CardDao DELETE; add Delete to CardRow in ReviewScreen (with confirm).
- ✅ Add `SessionRepository.interceptsBySurface(from, toExclusive): Flow<Map<String, Int>>` (GROUP BY surfaceId)
  and pass it to InsightsViewModel's `surfaceCounts`. (Room v2: index (atMs, surfaceId), `MIGRATION_1_2`,
  schema `app/schemas/.../2.json`, no destructive fallback.)

## From Phase 11 (sync, key holder, billing)
- ✅ Manifest `<application android:networkSecurityConfig="@xml/network_security_config">`.
- ✅ Setup should use `FreeTier.scheduleLimit` instead of `Features.FREE_SCHEDULE_LIMIT`.
- ✅ Apply remote detector configs: `DetectorConfigStore` (asset seed, refreshed from `SyncApi.detectors()` after each
  sync, higher version wins, persisted in filesDir); the accessibility service reads `container.detectorConfig`.
- ✅ Focus unlock UI should observe `RemoteUnlockRepository.outcomes` and handle NotSignedIn / NoKeyHolderLinked
  (approved → "Unlocked for 15 min", denied / no answer → friendly copy, links to Settings / Key holder).
- ✅ Token stored in plain private SharedPreferences; wrap with Keystore (`KeystoreTokenCipher`, legacy token migrated).
- ✅ Backend endpoints missing: DELETE /v1/me, GET/DELETE keyholder links, key-holder-readable weekly summary,
  POST /v1/billing/verify (or RTDN), POST /v1/auth/logout. (Android clients + MockWebServer tests; backend in 12b.)

## Release / compliance review (Android)
- ✅ B1: removed unused `PACKAGE_USAGE_STATS`.
- ✅ B2: removed `flagRetrieveInteractiveWindows`.
- ✅ B3: Disclosure follows Setup when Accessibility is off (plus Today banner, Settings link).
- ✅ B4: Disclosure (and the Accessibility service description) says it reads on-screen text, view ids and content
  descriptions of the listed apps, and that it presses Back / Home to leave a blocked section / app.
- ✅ B5: `BuildConfig.DEFAULT_API_BASE_URL` (debug `http://10.0.2.2:8787`, release `https://api.steady.app`
  placeholder); release rejects non-https server URLs (also a saved one); networkSecurityConfig referenced.
- ✅ B11: compileSdk / targetSdk 36 (platform installed, AGP 8.10.1 builds it); Play Billing 8.0.0 (no API changes
  needed). Other catalog libraries left as they are: their newest versions need AGP 9 / newer Kotlin.
- ✅ B12: POST_NOTIFICATIONS requested once at runtime (Android 13+) from Today before the first block.
- ✅ B13: `PlanGuard` + `GuardedPlanRepository`: during a strict block no removing distractions / schedules, softening
  BLOCK_APP, shortening the running block or making it non-strict, or changing key holder / unlock policy. Setup explains;
  a weaker server plan waits for a later sync.
- ✅ B15: `ProtectedApps` denylist (Settings, installers, Play Store, phone / dialer, contacts / SMS, emergency,
  System UI, Steady) enforced in PlanBuilder, `observedPackages` and `EnforcementDecider`; superset of the backend's 8.
- ✅ B19: "gets told" / "get a note" copy replaced ("can see your request in Steady").
- ✅ B20: Privacy policy link (`Links.PRIVACY_POLICY` placeholder) in Settings and Disclosure.
- ✅ 12b error codes mapped to copy: `otp_not_configured` / `otp_send_failed`, `billing_unavailable` /
  `billing_not_configured`, `purchase_invalid`, `purchase_in_use`, `protected_app`, `keyholder_limit`;
  nameless users show as "Your friend".
