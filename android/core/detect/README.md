# core/detect — detector engine

Pure Kotlin (JVM) module, package `app.steady.detect`. It decides whether the screen an app is
showing is a short-video surface (Instagram Reels, YouTube Shorts, Snapchat Spotlight, Facebook Reels),
so the enforcement layer (Phase 6) can hide just that section instead of the whole app.

- `ViewNode`: platform-free UI tree node. Phase 6 maps `AccessibilityNodeInfo` onto it.
- `DetectorConfig` / `Detector` / `Matcher`: the data in `shared/detectors/detectors.v*.json`.
  Every non-null field of a `Matcher` must hold on the **same** node. `descContains` is
  case-insensitive; the rest are exact. A matcher with no fields matches nothing.
- `DetectorCodec.decode(json)`: unknown keys (`_note`, future fields) are ignored.
- `SurfaceDetector.detect(pkg, root)`: depth-first pre-order walk, detectors in config order, first
  hit wins (matchers are tried in config order on each node). Hard cap of 4000 visited nodes per call.
  Iterative, so very deep trees cannot overflow the stack. Nothing here leaves the device.
- `SurfaceDetector.anchorSeen(pkg, root)`: is the detector's anchor (e.g. the tab bar) on screen?
- `DetectorHealth`: rolling window per package of `anchorSeen` samples. Healthy until the window is
  full; then healthy while the anchor rate is at least `minAnchorRate`.
- `FallbackLadder`: picks `HIDE_SECTION`, `BLOCK_APP` or `BLOCK_DOMAIN` from mode, health and
  whether the accessibility service is on.

> Note: the contract overload `FallbackLadder.level(mode: SurfaceMode, ...)` lives commented-out in
> `FallbackLadderRules.kt` until `core:rules` is merged (TODO(phase12)). Until then there is an
> `internal` overload taking `blockWholeApp: Boolean`.

## Running tests

```
cd android && ./gradlew :core:detect:build --console=plain
```

Tests load the real seed file `shared/detectors/detectors.v1.json` through the
`steady.detectors.seed` system property set in `build.gradle.kts`. Fake trees are built with the
tiny DSL in `src/test/.../TreeDsl.kt`:

```kotlin
tree { node("com.instagram.android:id/tab_bar") { node(desc = "Reels", selected = true) } }
```

## When Instagram / YouTube changes its view ids

Symptoms: `DetectorHealth` goes unhealthy for a package (the anchor stops being seen) and the ladder
falls back to `BLOCK_APP`, or Reels/Shorts stop being hidden.

1. On a real device with the new app version, dump the tree on the Reels/Shorts screen
   (`adb shell uiautomator dump` or Layout Inspector). Note stable resource ids and the selected tab's
   content description. Prefer `viewIdEndsWith` over full ids, and pair `descContains` with
   `selected: true` so the home feed (where the tab is visible but not selected) does not fire.
2. Edit the detector in `shared/detectors/`: add the new matcher to `anyOf` (keep the old one while
   older app versions are still around) and update `anchor` if the tab bar id changed.
3. **Bump `version`** (and add a new `detectors.vN.json` if the schema changes). Clients report
   `configVersion` with each `Detection`, so a stale config is easy to spot.
4. Add a fake tree for the new layout to `SurfaceDetectorTest` that fires, plus the home-feed tree
   that must not fire. Run the tests.
5. Ship: the backend serves the config at `GET /v1/detectors`; apps pick up the higher version
   without an app update. The seed file is also copied into the app assets at build time as the
   offline fallback.
