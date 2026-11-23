// TODO(phase12): enable when core:rules merged
//
// Steps: restore `api(project(":core:rules"))` in android/core/detect/build.gradle.kts, move the
// function below into `object FallbackLadder` (FallbackLadder.kt), and delete this file.
// Then add a test per SurfaceMode branch in FallbackLadderTest.
//
// package app.steady.detect
//
// import app.steady.rules.SurfaceMode
//
// /** Contract signature from docs/CONTRACTS.md (Phase 3). Belongs inside `object FallbackLadder`. */
// fun level(mode: SurfaceMode, healthy: Boolean, accessibilityOn: Boolean): EnforcementLevel =
//     level(blockWholeApp = mode == SurfaceMode.BLOCK_APP, healthy = healthy, accessibilityOn = accessibilityOn)
