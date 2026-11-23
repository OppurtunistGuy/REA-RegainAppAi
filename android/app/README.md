# android/app — Steady Android app

Compose app (single activity) for Steady: Setup → Today → Focus, the intercept overlay that hides
Reels / Shorts / Spotlight / Facebook Reels during scheduled blocks, flashcard swaps and review,
insights, and the optional account features (sync, key holder, Plus).

## Build and test

```
cd android
./gradlew build --console=plain --max-workers=2     # all modules: compile, unit tests (debug + release), lint
./gradlew :app:testDebugUnitTest                     # app unit tests only
```

`android/local.properties` must contain `sdk.dir=/opt/android-sdk` (git-ignored); compileSdk/targetSdk 36.
Unit tests are plain JVM (JUnit 4, kotlinx-coroutines-test, OkHttp MockWebServer); no Robolectric or device.

## Run against the local backend

1. `cd backend && npm install && npm run dev` (listens on port 8787).
2. Install the **debug** build on an emulator: `./gradlew :app:installDebug`.
3. Debug builds default to `http://10.0.2.2:8787` (the emulator's alias for your computer's localhost);
   cleartext is allowed only to 10.0.2.2 / localhost by `src/debug/res/xml/network_security_config.xml`.
   On a physical device change the address in Settings → Server (e.g. your computer's LAN IP over https,
   or `adb reverse tcp:8787 tcp:8787` and `http://localhost:8787`).
4. Settings → Account: sign in with any contact; in dev the backend logs the one-time code.

Release builds default to `https://api.steady.app` (placeholder, `buildConfigField` in `build.gradle.kts`),
reject non-https server addresses and permit no cleartext at all.

## Architecture

```
 MainActivity ── EXTRA_ROUTE deep links ──▶ SteadyNavHost (nav/) ── RouteTable (pure: routes, start, deep links)
     │                                          │ Navigator
     ▼                                          ▼
 SteadyApp ─ builds ─▶ AppContainer (di/)    feature/* screens + ViewModels (pure *Models / *Logic, unit-tested)
     │                   │
     │                   ├─ planRepo = GuardedPlanRepository(PlanGuard) ─▶ LocalPlanRepository ─┐
     │                   ├─ sessionRepo / cardRepo / dayRepo / local unlocks ───────────────────┼─▶ Room steady.db (data/db, v2)
     │                   ├─ rules  = ZoneAwareRules (RulesEngine for the current device zone)   │
     │                   ├─ detectorConfig = DetectorConfigStore (asset seed ⇄ GET /v1/detectors, persisted)
     │                   └─ remote = RemoteModule (remote/) ── ApiClient (OkHttp) ──▶ backend /v1
     │                           SyncCoordinator · SessionUploader · RemoteUnlockRepository (polls key holder)
     │                           RemoteEntitlementRepository (Play Billing + /v1/entitlements + /v1/billing/verify)
     │                           AccountStore (token encrypted with an AndroidKeyStore AES/GCM key) · AuthGuard (401)
     │
     └─ plan changes ─▶ BlockScheduler.sync ─▶ AlarmManager (BlockAlarmReceiver) + FocusService (notification)
                         BootReceiver: boot / update / time / time-zone change → sync again

 SteadyAccessibilityService (enforcement/) ── reads plan, grants, rules, detectorConfig from AppContainer
     ├─ EnforcementDecider (pure; ProtectedApps never acted on) → BACK / HOME
     ├─ records the intercept (the only recorder of real opens)
     └─ InterceptOverlay → InterceptContent (same sheet as the in-app Intercept route)
          └─ "60-second swap" → launchIntent(ROUTE_SWAP) → MainActivity → Swap
```

How the features connect:

- **Setup** saves the plan (free tier: `FreeTier.scheduleLimit`); SteadyApp's plan collector re-arms the
  block alarm. If Accessibility is off, Setup continues to the **Disclosure** (also linked from a Today
  banner and Settings). While a strict block runs, `PlanGuard` refuses edits that would weaken it.
- **Today** shows the day strip and next block; "Start now" opens **Focus**; the debug "Try opening
  Instagram" opens the in-app **Intercept** with `recordOpen = true` (the only in-app path that records).
- **Focus** runs the block; emergency unlock self-grants or asks the key holder through
  `RemoteUnlockRepository`, and follows its `outcomes` (approved / denied / no answer). Signed out or no
  linked key holder → the sheet links to Settings or Key holder.
- **Recap / Swap / Review** use `core/learning`; **Insights** aggregates sessions, per-day intercepts and
  `interceptsBySurface`.
- **Settings** (account, sync, plan, privacy, delete synced data via `DELETE /v1/me`, server address) and
  **Partner** (invites, key-holder links both ways with Remove, weekly summaries, unlock requests).

## Layout (`src/main/java/app/steady/`)

| Path | What |
|------|------|
| `MainActivity.kt`, `SteadyApp.kt`, `Links.kt` | Activity (deep links), Application (container, block-alarm upkeep), external URLs and build-type API config. |
| `di/AppContainer.kt` | `AppContainer` + `DefaultAppContainer`, `DeviceClock`, `ZoneAwareRules`. |
| `data/` | Contract repositories, `PlanGuard`, `DetectorConfigStore`, Room DB (`db/`, schemas in `app/schemas/`, migration 1→2), local repositories. |
| `nav/` | Type-safe routes, `Navigator`, `RouteTable`, `SteadyNavHost`. |
| `enforcement/` | Accessibility service, decider, overlay, FocusService, BlockScheduler + receivers, `ProtectedApps`, notification permission. See its README. |
| `remote/` | Backend client and account features. See its README. |
| `feature/*` | One package per screen. |
| `ui/` | Theme tokens and shared components. |

## Privacy

Everything works signed out and stays on the phone. Intercept events store only surface id + time; no
screen content is ever stored or sent. Room data, prefs and the token are excluded from backup.
