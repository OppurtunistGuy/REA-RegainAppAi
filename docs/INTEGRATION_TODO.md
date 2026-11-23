# Phase 12 integration checklist (collected from phase reports)

## From Phase 6 (enforcement)
- Manifest: add `RECEIVE_BOOT_COMPLETED`, `<receiver .enforcement.BlockAlarmReceiver exported=false>`,
  `<receiver .enforcement.BootReceiver exported=false>` with BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED.
- MainActivity: read `EXTRA_ROUTE` / `EXTRA_SCHEDULE_ID` via `launchRouteFor(...)` in onCreate and onNewIntent;
  SteadyNavHost needs to accept a pending route.
- Call `BlockScheduler.sync(context)` from SteadyApp start and after plan save.
- Link to `Disclosure` route from Setup / Today / Settings.
- Delete unused `accessibility_service_description` from strings.xml.
- InterceptUiState has no `strict` field; consider adding.
- `container.rules` captures ZoneId at creation; rebuild on TIMEZONE_CHANGED.

## From Phase 5 (shell)
- Verify `res/values/font_certs.xml` against Android Studio's generated file.
- Free 2-schedule limit enforced in Setup; Phase 11 FreeTier may duplicate it.

## From Phase 10 (extension)
- vitest 3.2.x has audit advisories; upgrade to vitest 4.1.12+ when npm can install it.
- Extension self-grants unlocks even with a key holder; consider POST /v1/unlock-requests.
- `end == start` schedules = 24h (rules engine agrees).

## From Phase 9 (backend)
- Bearer tokens never expire; add expiry/rotation.
- Missing `DELETE /v1/me` for "delete my synced data".

## From Phase 7 (intercept + focus)
- Overlay (Phase 6) should pass raw schedule label and compute opensToday/opensBaseline like InterceptViewModel
  (BASELINE_DAYS, InterceptCopy.baseline) instead of the placeholder 11.
- Check intercept double-counting between in-app Intercept screen and the overlay.
- Focus session starts when Focus opens, not at schedule start.
- Pending key-holder requests are shown but polled only by Phase 11.

## From Phase 8 (learning UI)
- Add `CardRepository.delete(ids)` + CardDao DELETE; add Delete to CardRow in ReviewScreen.
- Add `SessionRepository.interceptsBySurface(from, toExclusive): Flow<Map<String, Int>>` (GROUP BY surfaceId)
  and pass it to InsightsViewModel's `surfaceCounts`.

## From Phase 11 (sync, key holder, billing)
- Manifest `<application android:networkSecurityConfig="@xml/network_security_config">`.
- Setup should use `FreeTier.scheduleLimit` instead of `Features.FREE_SCHEDULE_LIMIT`.
- Apply remote detector configs: `container.detectorConfig` is a fixed asset; use SyncApi.detectors() when newer.
- Focus unlock UI should observe `RemoteUnlockRepository.outcomes` and handle NotSignedIn / NoKeyHolderLinked.
- Token stored in plain private SharedPreferences; wrap with Keystore.
- Backend endpoints missing: DELETE /v1/me, GET/DELETE keyholder links, key-holder-readable weekly summary,
  POST /v1/billing/verify (or RTDN), POST /v1/auth/logout.
