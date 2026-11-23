# core/rules

Pure Kotlin (JVM) rules engine for Steady, package `app.steady.rules`. No Android dependencies.

- **Plan model** (`Plan`, `Surface`, `Schedule`, `KeyHolder`, `UnlockPolicy`, …) matching
  `shared/schema/plan.schema.json`, with `PlanCodec` (strict: unknown keys rejected, defaults written,
  schedule times/days validated).
- **`serial.InstantSerializer`**: `java.time.Instant` as ISO-8601 string (reused by `core/learning`).
- **`RulesEngine(zone)`**: `state`, `nextBlock` (strictly after now, up to 8 days ahead), `isBlocked`
  (listed surface or `site:` domain/subdomain from `siteBlocks`, active block, no live grant), `blocksOn`.
  Start inclusive, end exclusive; `end < start` crosses midnight and belongs to the start day;
  `end == start` is 24 hours. DST via `ZoneId` (a start in a spring-forward gap shifts forward).
  Overlap tie-break: strict first, then later end, then lowest id.
- **`UnlockFlow`**: WAITING → NEEDS_REASON (reason < 8 non-space chars) → NEEDS_KEYHOLDER / READY;
  `selfGrant` makes an `UnlockGrant` valid in `[from, until)`.
- **`EnergyPlanner`**: LOW 15, OKAY 25, SHARP 40 minutes.

## Tests

```
cd android && ./gradlew :core:rules:test --console=plain
```

`TestVectorsTest` runs every case in `shared/schema/test-vectors.json` (passed in via the `vectors`
system property) for both `state` and `nextBlock`. The extension's TypeScript port uses the same file.
