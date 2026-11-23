# Steady build plan — 12 phases

Steady (working name) is an ADHD-friendly focus and study app. Strict mode is free and starts on a schedule.
Urges get caught and turned into study reps. See `prototype/steady-prototype.html` for the target UX and
`reports/ADHD focus app long term strategy.md` for the reasoning.

All interfaces between phases are fixed in [`CONTRACTS.md`](CONTRACTS.md). A phase may add things,
but it must not change a contract signature without updating CONTRACTS.md in the same commit.

## Repo layout

```
shared/            JSON schemas, detector config, OpenAPI spec (source of truth for all clients)
android/           Gradle build: core/* are pure Kotlin (JVM) modules, app/ is the Android app
backend/           TypeScript API (Node 22, Hono, node:sqlite)
extension/         Chrome MV3 extension (TypeScript, esbuild)
docs/              This plan, contracts, policy docs
prototype/         Clickable HTML prototype
```

## Phases and waves

| # | Phase | Owns (paths) | Depends on | Wave |
|---|-------|--------------|-----------|------|
| 1 | Foundation and contracts | `docs/`, `shared/`, Gradle skeleton, CI | – | 0 |
| 2 | Rules engine | `android/core/rules/` | 1 | A |
| 3 | Detector engine | `android/core/detect/` | 1 | A |
| 4 | Learning engine | `android/core/learning/` | 1 | A |
| 5 | App shell, data, Setup + Today screens | `android/app/` (shell, `data/`, `di/`, `nav/`, `ui/`, `feature/setup`, `feature/today`) | 1 | A |
| 9 | Backend API | `backend/` | 1 | A |
| 10 | Chrome extension | `extension/` | 1 | A |
| 6 | Android enforcement | `android/app/.../enforcement/`, `res/xml/accessibility_service_config.xml` | 2, 3, 5 | B |
| 7 | Intercept + Focus UI | `android/app/.../feature/intercept/`, `feature/focus/` | 2, 5 | B |
| 8 | Learning UI, Recap, Insights | `android/app/.../feature/learning/`, `feature/recap/`, `feature/insights/` | 4, 5 | B |
| 11 | Sync, key holder, billing, privacy | `android/app/.../remote/`, `feature/partner/`, `feature/paywall/`, `feature/settings/` | 5, 9 | B |
| 12 | Integration, QA, release prep | whole repo | all | C |

Wave A runs six phases in parallel. Wave B runs four in parallel once wave A is merged. Wave C is integration.

## Definition of done (every phase)

- Code builds: `cd android && ./gradlew :<module>:build` / `npm test` in backend or extension.
- Unit tests for all logic. Pure modules aim for full branch coverage of the public API.
- No screen contents, messages or watched-video data ever leave the device. (Privacy rule, non-negotiable.)
- Wellness wording only. Never claim to treat, reduce or diagnose ADHD symptoms.
- A short `README.md` in the owned directory saying what it does and how to run tests.
- Commit on your branch with a clear message. Do not edit files owned by another phase except
  where CONTRACTS.md marks a shared "slot".
