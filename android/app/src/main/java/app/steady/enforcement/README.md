# enforcement (Phase 6)

Hides Reels / Shorts / Spotlight / Facebook Reels (and whole blocked apps) during scheduled blocks.

| File | What |
|------|------|
| `SteadyAccessibilityService` | Debounces window events (250 ms). Checks the rules first; only if something about the foreground package is blocked now does it read the tree, run `SurfaceDetector`, record `DetectorHealth`, and ask `EnforcementDecider`. BACK (section) or HOME (app) once, then the overlay. Records only surface id + time. |
| `EnforcementDecider` | Pure decision: `app:<pkg>` block → HOME; section via `FallbackLadder` (BLOCK_APP mode / unhealthy → HOME, HIDE_SECTION + detected → BACK). One action per package per 1.5 s. |
| `NodeTree` | Lazy `AccessibilityNodeInfo` → `ViewNode` adapter (`NodeSource` interface for tests), 4000-node cap, recycles every node it opened on `close()`. |
| `InterceptOverlay` | `InterceptContent` in a `TYPE_ACCESSIBILITY_OVERLAY` ComposeView with its own lifecycle / saved-state / view-model owners. |
| `FocusService` | specialUse foreground service with the quiet "Coaching block · 1:12 left" notification; stops itself when no block is active. |
| `BlockScheduler` | Next start/end via `RulesEngine`, one inexact `setAndAllowWhileIdle` alarm. `BlockAlarmReceiver`, `BootReceiver`. |
| `AccessibilityStatus` | Parses `ENABLED_ACCESSIBILITY_SERVICES`; `rememberAccessibilityEnabled()` refreshes on resume. |
| `Launch.kt` | `EXTRA_ROUTE = "steady.route"` deep links (`swap`, `focus`, `today`, `disclosure`) → `launchRouteFor()`. |

Tests: `cd android && ./gradlew :app:testDebugUnitTest --tests 'app.steady.enforcement.*'`

| `ProtectedApps` | Packages never blocked or acted on (Settings, installers, Play Store, dialer/phone, emergency, Steady). |
| `NotificationPermission` | Asks for POST_NOTIFICATIONS once (Android 13+) from Today, before the first block. |

The manifest declares `RECEIVE_BOOT_COMPLETED`, `BlockAlarmReceiver` and `BootReceiver` (boot, package
replaced, time set, time-zone change). `SteadyApp` calls `BlockScheduler.sync` on start and on every plan
change. The overlay state uses `InterceptStateBuilder.forOverlay` (today vs. the mean of the previous 7 days);
the service is the single place real opens are recorded.
