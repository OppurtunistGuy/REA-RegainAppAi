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
