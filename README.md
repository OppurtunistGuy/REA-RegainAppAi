# Steady (working name)

An ADHD-friendly focus and study app. Strict mode is free and starts on a schedule.
Urges get caught and turned into study reps.

- Plan: [`docs/PHASES.md`](docs/PHASES.md)
- Contracts between modules: [`docs/CONTRACTS.md`](docs/CONTRACTS.md)
- Prototype: [`prototype/steady-prototype.html`](prototype/steady-prototype.html)
- Research: [`reports/`](reports/)

| Part | Path | Run tests |
|------|------|-----------|
| Android app + core modules | `android/` | `./gradlew build` (needs `local.properties` with `sdk.dir`) |
| Backend API | `backend/` | `npm test` |
| Chrome extension | `extension/` | `npm test` |

## Release docs

Release, compliance and QA docs live in [`docs/release/`](docs/release/). Start with the launch
checklist: it lists the open **blocking issues** (places where the code and a store policy disagree).

- [Launch checklist and blocking issues](docs/release/launch-checklist.md)
- [Play Accessibility API declaration and prominent disclosure](docs/release/play-accessibility-declaration.md)
- [Play Data safety answers](docs/release/play-data-safety.md)
- [Privacy policy draft](docs/release/privacy-policy.md) (needs legal review)
- [Wellness claims guide](docs/release/wellness-claims-guide.md)
- [Store listings (Play and Chrome Web Store)](docs/release/store-listing.md)
- [Manual QA test plan](docs/release/qa-test-plan.md)
