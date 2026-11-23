# android/app — Steady Android app

Compose app shell (Phase 5): theme, navigation, local-first data layer, DI, and the Setup and Today
screens. Screens owned by later phases are stubs with fixed signatures (see `docs/CONTRACTS.md`).

## Build and test

```
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

`android/local.properties` must contain `sdk.dir=/opt/android-sdk` (git-ignored).
Unit tests are plain JVM (JUnit 4 + kotlinx-coroutines-test); no Robolectric or device is needed.

## Layout (`src/main/java/app/steady/`)

| Path | What |
|------|------|
| `MainActivity.kt`, `SteadyApp.kt` | Edge-to-edge Compose host; Application builds the `AppContainer` (`context.appContainer`). |
| `di/AppContainer.kt` | `AppContainer` interface + `DefaultAppContainer`. Phase 11 swaps `syncApi` / `entitlements` here only. |
| `data/Repositories.kt` | Contract repository interfaces, `Entitlement`/`Tier`, plus `DayRepository` (tasks + energy), `EmptyPlan`, `Plan.isConfigured`. |
| `data/db/` | Room DB `steady.db` (plan JSON blob via `PlanCodec`, sessions, intercept events, cards, unlock grants/requests, tasks, energy). Schemas exported to `android/app/schemas/`. |
| `data/local/` | Local repository implementations over the DAOs. |
| `data/LocalOnlyServices.kt` | `NoopSyncApi`, `FreeEntitlementRepository`, feature flag names. |
| `nav/Routes.kt` | Type-safe `@Serializable` routes + `Navigator` interface used by every screen. |
| `nav/SteadyNavHost.kt` | Maps routes to screens. Starts at Setup until a plan is saved, then Today. |
| `ui/theme/` | Prototype tokens (light/dark), Atkinson Hyperlegible body, Bricolage Grotesque display, JetBrains Mono labels via downloadable Google Fonts (`res/values/font_certs.xml`); falls back to system sans. Extra tokens: `Steady.colors.urge` etc. |
| `ui/components/` | Prototype primitives: `OptionChip` (.opt), `PrimaryButton` (.cta), `GhostButton`, `SteadyCard`, `Pill`, `SectionLabel`, `StubScreen`. All touch targets ≥ 48dp. |
| `feature/setup/` | Distractions, schedules (two presets + custom with time pickers), key holder → saves Plan. `PlanBuilder` is pure and unit-tested. |
| `feature/today/` | Energy check-in, day strip (`RulesEngine.blocksOn`), next-block countdown, tasks with tiny first step, Start now / Try opening Instagram. |
| `feature/*` (others) | Stubs for phases 6, 7, 8, 11. Replace the body; keep the signature `(container, nav[, route])`. |
| `enforcement/` | Stubs for Phase 6 (accessibility service, foreground service, overlay). Declared in the manifest already. |

## Contract stubs (temporary)

`src/stubs/kotlin/` holds minimal copies of the `core/rules`, `core/detect` and `core/learning` types
this module uses, in their real packages, so the app builds before the core modules land.
`TODO(phase12)`: delete `src/stubs/`, remove the `srcDir("src/stubs/kotlin")` line and restore the
three `implementation(project(":core:…"))` lines in `build.gradle.kts`.

## Privacy

Everything is stored locally. Intercept events store only surface id + time. No screen content is
ever stored or sent; Room data is excluded from cloud backup (`res/xml/data_extraction_rules.xml`).
