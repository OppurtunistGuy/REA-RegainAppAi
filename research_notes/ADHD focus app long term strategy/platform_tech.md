# Platform Technical Feasibility & Risk: App Blockers and In-App Content Blockers (Reels/Shorts), 2026

Research method note: research date 2026-11-22. All page fetches (support.google.com, dev.to, habitdoom.com, malwarebytes.com, anonhaven.com) failed with DNS errors in this environment, so the findings below come from **WebSearch result snippets and summaries only**, not full-page reads. Treat quoted details as snippet-level and verify against the primary page before relying on them. Items from my own background knowledge (not sourced in this session) are marked **[background, unverified]** and are kept in Inferences/Gaps.

---

## 1. Android: AccessibilityService policy, permissions, background limits, and how Reels/Shorts detection works

### Takeaway
On Android, in-app section blocking (Reels/Shorts) is technically possible today only through AccessibilityService. That path faces three growing risks. First, Play policy says only disability-support apps may declare `isAccessibilityTool`, so blockers must use the prominent-disclosure path. Second, Android 17's Advanced Protection Mode (AAPM) revokes accessibility access from non-accessibility apps for users who opt in. Third, Android 13/15 "Restricted Settings" makes sideloading hard. Whole-app blocking can fall back to UsageStatsManager plus an overlay, which is less fragile but cannot see inside an app.

### Cited Findings
**Play AccessibilityService policy**
- Only services "designed to help disabled people to access their devices or otherwise overcome challenges stemming from their disabilities" are eligible to declare themselves accessibility tools (`isAccessibilityTool`). Apps that declare it are exempt from the prominent disclosure and consent requirement. — [Play Console Help: Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en&amp=&ref_topic=12798286)
- Play's permissions guidance warns developers not to misuse the flag ("don't use it if your app is not a disability support tool"). Apps that use AccessibilityService without the flag must show clear in-app disclosure, obtain user consent, and complete the accessibility declaration in Play Console. — [Play Console Help: Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/16558241)
- A Malwarebytes report (March 2026) says Google is enforcing stricter accessibility rules: only apps whose core purpose is accessibility will be allowed to use it, and "password managers or automation apps aren't getting to the accessibility API anymore." The snippet does not name blockers. It was not clear from the snippet whether this describes Play policy or only Android 17 AAPM. — [Malwarebytes, Mar 2026](https://www.malwarebytes.com/blog/mobile/2026/03/google-cracks-down-on-android-apps-abusing-accessibility)
- A secondary blog describes an "April 2026 permissions crackdown" on Google Play. The details were not retrievable. — [Sarvaya blog](https://sarvaya.in/blog/google-play-april-2026-permissions-policy)
- No reporting was found of a specific named blocker being removed from Play for an accessibility violation in 2025–2026. General rules: a removed app stays unavailable until a compliant update is submitted, and repeated or egregious violations can lead to suspension. — [Play Console Help: My app has been removed](https://support.google.com/googleplay/android-developer/answer/2477981?hl=en)

**Android 17 Advanced Protection Mode (AAPM)**
- In Android 17 Beta 2 (March 2026), AAPM restricts the AccessibilityService API to apps classified as accessibility tools: screen readers, switch input, voice input, and Braille. Antivirus, automation, assistant, cleaner, password manager, and launcher apps do not qualify. — [The Hacker News, Mar 2026](https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html); [Android Authority](https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860/)
- When AAPM is on, accessibility privileges already granted to non-accessibility apps are revoked automatically. Users cannot grant the permission again unless they turn AAPM off. Android Authority's test on a Pixel 9a could not grant accessibility to dynamicSpot while AAPM was on. — [Android Authority](https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860/); [Security Affairs](https://securityaffairs.com/189497/security/advanced-protection-mode-in-android-17-prevents-apps-from-misusing-accessibility-services.html); [Gadget Hacks](https://android.gadgethacks.com/news/android-17-beta-2-blocks-popular-automation-apps/)
- No source confirmed how this behaves in the stable Android 17 release, and none named focus or blocker apps. — (same sources; gap)

**Restricted Settings (sideloading)**
- Android 13 blocks sideloaded apps from enabling Accessibility and Notification Listener access ("unavailable"). The owner can authenticate through a hidden per-app option to allow it. — [Esper](https://www.esper.io/blog/android-13-sideloading-restriction-harder-malware-abuse-accessibility-apis); [Android Authority](https://www.androidauthority.com/android-13-sideloading-apps-restrictions-3161162)
- Android 15 extends the restriction: sideloaded apps can no longer easily be granted draw-over-screen (overlay), usage stats, device admin, and more. The CDD requires restricted settings on by default. Users can still lift it per app. — [Android Authority](https://androidauthority.com/android-15-restricted-settings-sideloading-3481098); [9to5Google](https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/)
- Some HyperOS (Xiaomi) updates silently disable accessibility services, so users must re-enable them. — [CleanBrowsing support](https://cleanbrowsing.org/support/mobile/disable-restricted-settings-android)
- Open-source blocker LifeIsShort's PR #19 notes that Android "may still warn about restricted settings" because of the accessibility capability it requests. — [LifeIsShort PR #19](https://github.com/Feuscel/LifeIsShort/pull/19)

**Foreground services and overlays (Android 14/15)**
- Android 14 requires every foreground service to declare a type, and Play Console requires a declaration of those types on the App content page. `specialUse` is new in Android 14. Google recommends WorkManager or user-initiated data transfer jobs for work that fits no listed type. — [Android FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes); [Play Help: FGS & full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
- A secondary blog says app blockers usually fall under `specialUse` and need a manifest property that explains the use to reviewers. — [DEV: Android foreground services in 2026](https://dev.to/joe_wang_6a4a3e51566e8b52/android-foreground-services-in-2026-what-changed-and-how-to-adapt-2o3d)
- Android 15: for apps targeting it, holding SYSTEM_ALERT_WINDOW is no longer enough to start a foreground service from the background. The app must also have a visible `TYPE_APPLICATION_OVERLAY` window showing before it starts the service. — [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)
- Android 15 limits `dataSync` and `mediaProcessing` foreground services to 6 hours in any 24-hour period, after which `Service.onTimeout()` is called. — [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)

**Alternatives to Accessibility**
- A DEV article describes blocking apps on Android "without an AccessibilityService" (the UsageStats/overlay approach). Only the title was retrievable. — [DEV: How to block apps on Android without an AccessibilityService](https://dev.to/rexa/how-to-block-apps-on-android-without-an-accessibilityservice-5gog)
- Local VpnService DNS filtering is used by focus apps. For example, Loop Lock uses "Android's VpnService API to perform domain filtering directly on your device," which is "not a traditional VPN," and forwards unblocked queries to public DNS. — [Loop Lock on Google Play](https://play.google.com/store/apps/details?id=com.fys.looplock)
- Every app that uses VpnService must complete a Play Console declaration (original deadline August 31, 2023). Apps outside the allowed categories must remove VPN services from all tracks. Earlier guidance required encrypting data to the tunnel endpoint. — [Play Help: VpnService policy](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en)

**How Reels/Shorts detection works (Regain, ScreenZen, open source)**
- ScreenZen's privacy policy says the Android app uses the Accessibility service to block apps and websites. It reads the on-screen app name and browser address, and reads "specific parts of the Instagram, YouTube, Snapchat and Facebook screens, to detect Reels, Shorts, Spotlight and Stories." It does not read password fields or record the screen, and Accessibility data stays on the device unless the user sends a diagnostic report. On iOS, ScreenZen instead opens these sites in a built-in browser and "adds styles and scripts to these sites to hide features such as Shorts and feeds." — [ScreenZen privacy policy](https://screenzen.co/policies/privacy-policy)
- A comparison blog says Regain has a dedicated "Block Reels and Shorts" feature for Instagram, YouTube, Snapchat Spotlight, and Facebook, built on an Accessibility Service rather than an overlay. This is a secondary source and may be a competitor. — [Habitdoom: Best App Blockers for Android 2026](https://habitdoom.com/blog/best-app-blockers-android-2026); [Minulo: ScreenZen alternatives](https://minulo.app/en/screenzen-alternatives.html)
- Open-source pattern: reelblock is "about 120 lines of Java, no root, no internet permission." It closes Instagram Reels and YouTube Shorts and leaves the rest of each app usable. — [SuzalShrestha/reelblock](https://github.com/SuzalShrestha/reelblock)
- Open-source pattern: insta-block matches Instagram view IDs and warns that "if Instagram renames them in a future update, blocking may stop working until the ID list in ReelsAccessibilityService.kt is updated." — [rustyisacat/insta-block](https://github.com/rustyisacat/insta-block)
- Other open-source repos with Reels-detection issues: zen-mode issue #71 (the Reels viewing window expires only after another accessibility event), block-instagram-reels issues #8 and #13 (package-filtered service config; verifying real selectors on device), App-Block PR #35 (fixing a "blink" and over-blocking of the feed), and ScrollGuard issue #1 (Reels-hiding spec). — [zen-mode #71](https://github.com/MaximilianMauroner/zen-mode/issues/71); [block-instagram-reels #8](https://github.com/Moritz-Staat/block-instagram-reels/issues/8); [#13](https://github.com/Moritz-Staat/block-instagram-reels/issues/13); [App-Block PR #35](https://github.com/skytheacademic/App-Block/pull/35); [ScrollGuard #1](https://github.com/demontgolfierguillaume-prog/ScrollGuard/issues/1)

### Inferences
- Blocker apps will almost certainly not qualify for `isAccessibilityTool`. Plan for the prominent-disclosure path: a full-screen disclosure before the system settings intent, a Play Console declaration, and a demo video for review. Google has tightened this area in 2021, 2022, 2026 (AAPM), and possibly April 2026 (Play). AccessibilityService is the core and riskiest dependency.
- AAPM is opt-in today and aimed at high-risk users. If Google later makes AAPM-style gating the default, or applies it in Play review, accessibility-based in-app blocking on Android could become impossible for ordinary apps. Build so that the product degrades to whole-app blocking through UsageStats plus an overlay, and to DNS/VPN-based site blocking.
- Distributing outside Play (direct APK) does not get around the restrictions: Android 15 Restricted Settings applies to accessibility, overlay, usage stats, and device admin.
- Detection should combine several signals (view IDs, content descriptions, class names, and screen geometry such as a full-screen vertical pager), be configurable remotely, and have a "detection health" check in telemetry, because single view-ID matching breaks.
- [background, unverified] The UsageStatsManager approach polls `queryEvents` every few hundred milliseconds from a foreground service. That costs battery and adds latency. Android has no public Digital Wellbeing API for third parties: the Digital Wellbeing app limits are system features, not callable APIs. Device Admin uninstall protection is deprecated for most uses, and Play restricts using it to block uninstalls.

### Gaps
- The full current text of the Play AccessibilityService policy could not be read (fetch failed), including any 2025–2026 edits and the exact wording on digital-wellbeing use.
- No confirmed case of a named blocker (Regain, ScreenZen, AppBlock, ScrollGuard) being removed or rejected over accessibility in 2025–2026 was found.
- Stable Android 17 AAPM behavior, and whether it extends beyond opted-in users, is unknown. Android 16 behavior changes specific to blockers were not found.
- No sourced data was found on UsageStats polling battery cost, on Device Admin's status, or on Digital Wellbeing APIs.
- Exact AppBlock and ScrollGuard (commercial) detection methods were not found.

---

## 2. iOS: FamilyControls, ManagedSettings, DeviceActivity, Shortcuts, and 2025–2026 changes

### Takeaway
On iOS, the Screen Time API family (FamilyControls, ManagedSettings, DeviceActivity) blocks whole apps, categories, and web domains through opaque tokens. It cannot block sections inside an app such as Reels or Shorts. The shield can be only lightly customized. It requires an Apple-approved entitlement for each extension target, and developers report recurring reliability bugs in iOS 26.x that iOS 27 (WWDC 2026) did not fix. In-app blocking on iOS is possible only by moving the user out of the native app: an in-app browser with injected CSS/JS (ScreenZen's approach) or friction through Shortcuts automations (one sec).

### Cited Findings
**Capabilities and limits**
- ManagedSettings applies the block: setting `store.shield.applications` to a set of app tokens locks those apps at the OS level. `applicationCategories` is a separate shield property. — [DEV: Apple's Screen Time API Broke Me](https://dev.to/the_unmanaged_boy/apples-screen-time-api-broke-me-1d00)
- "Apple's own native Screen Time has far more internal control than the API gives third-party developers." The shield allows only text and background color changes, with no brand assets or gradients. — [Nikki Eke, DEV / Medium](https://dev.to/nikki_eke/what-no-one-tells-you-about-building-with-apples-screen-time-api-3o98)
- ShieldActionDelegate can respond only with `.none`, `.close`, or `.defer`. Extensions have no UIApplication, no NSExtensionContext, and cannot open URLs, so the shield cannot deep-link back into your app. — [DEV: Building an iOS app blocker with FamilyControls in Expo](https://dev.to/yarmolenkod/building-an-ios-app-blocker-with-the-screen-time-api-familycontrols-in-expo-what-the-docs-dont-2emi)
- The app cannot reliably read back which apps are currently shielded across app launches. Apps and extensions don't share store state, so you need shared persistence (an App Group). — [DEV (Expo guide)](https://dev.to/yarmolenkod/building-an-ios-app-blocker-with-the-screen-time-api-familycontrols-in-expo-what-the-docs-dont-2emi); [Apple Dev Forums: Managed Settings](https://developer.apple.com/forums/tags/managed-settings?page=3)
- The Simulator does not render shields and often fires monitors late, so testing must happen on a device. — [DEV (Expo guide)](https://dev.to/yarmolenkod/building-an-ios-app-blocker-with-the-screen-time-api-familycontrols-in-expo-what-the-docs-dont-2emi)
- The DeviceActivityMonitor extension is reportedly killed at about a 6 MB memory limit. This comes from a community answer, not Apple. — [plainphone GitHub issue #163](https://github.com/plainphone/plainphone/issues/163)
- A developer reported that the generic "Restricted" shield hides which app blocked the content, and that developers cannot opt out of it. — [Apple Dev Forums: Screen Time tag](https://developer.apple.com/forums/tags/screen-time)

**Entitlement approval**
- The Family Controls entitlement is needed for both development and distribution. The Account Holder must request the distribution entitlement, and without approval "the APIs simply wouldn't work." — [Nikki Eke, DEV](https://dev.to/nikki_eke/what-no-one-tells-you-about-building-with-apples-screen-time-api-3o98)
- Each extension target (shield, monitor, report) needs its own approval. Developers report waits of more than 2 weeks, one request filed March 9, 2026 with no response, and the main app approved while the shield extension was still pending. — [Apple Dev Forums: Family Controls entitlement stuck](https://developer.apple.com/forums/thread/809208); [Apple Dev Forums: Family Controls tag](https://developer.apple.com/forums/tags/family-controls)

**Reliability bugs (2025–2026)**
- On iOS 26.2 (23C55), `DeviceActivity eventDidReachThreshold` fires with 0 Screen Time minutes, which causes early shielding. — [Apple Dev Forums thread 811305](https://developer.apple.com/forums/thread/811305)
- The Screen Time API reports false positives to the DeviceActivityMonitor extension in iOS 26.2 and 26.3. — [Apple Dev Forums thread 812472](https://developer.apple.com/forums/thread/812472)
- After updating to iOS 26, FamilyActivityPicker no longer opens and logs no console errors (single report). — [Apple Dev Forums: Family Controls](https://developer.apple.com/forums/tags/family-controls)
- An iOS 27.0.1 child device did not enforce third-party ManagedSettings shields when the parent ran iOS 18.7.1, while system Screen Time kept working. — [Apple Dev Forums: Family Controls](https://developer.apple.com/forums/tags/family-controls)
- Revoking authorization in Settings sends no signal to the app while it is in the background. — [Apple Dev Forums: Family Controls](https://developer.apple.com/forums/tags/family-controls)

**WWDC 2026 / iOS 27**
- Third-party blockers still use FamilyControls and ManagedSettings, which iOS 27 leaves in place. New work focused on parents: Time Allowances, Screen Time Schedule, and Ask to Browse. The Declared Age Range API and PermissionKit matter mainly for apps for minors. DeviceActivity throttling and dropped callbacks are reported unchanged in the iOS 27 developer beta. iOS 27 entered public beta on July 13, 2026. These are blog sources and opinion-heavy. — [Habitdoom: What WWDC 2026 changed](https://habitdoom.com/blog/wwdc-2026-screen-time-wishlist); [Habitdoom: iOS 27 Screen Time changes](https://habitdoom.com/blog/ios-27-screen-time-changes)

**Shortcuts approach (one sec)**
- one sec uses a personal Shortcuts "App is opened" automation, one per target app, that runs "Activate one sec" to show an intervention. A second "App is closed" automation runs "Log App Closing" to support intentional app switching with a grace period. — [one sec setup](https://tutorials.one-sec.app/setup-ios); [Intentional App Switching](https://tutorials.one-sec.app/en/articles/3310146)
- On iOS 27+, one sec can install the automations through an "Add Intervention to Shortcuts" import flow, but the user must still turn the automation on. — [one sec: Initial Shortcuts setup on iOS 27+](https://tutorials.one-sec.app/en/articles/10309122)
- Known failure modes: interventions are skipped during a grace period and for about 2 minutes after the iPhone restarts, and they lag on older devices. Automation performance was "heavily improved with iOS 18.3." The changelog mentions improvements for apps connected through Screen Time on iOS 26.5+, so one sec now also uses the Screen Time API. — [one sec troubleshooting](https://tutorials.one-sec.app/en/articles/3262210)
- ScreenZen iOS hides Shorts by opening the web version in an in-app browser with injected CSS/JS. — [ScreenZen privacy policy](https://screenzen.co/policies/privacy-policy)

### Inferences
- No public iOS API lets a third-party app detect or block a section of another native app. "Block Reels only" on iOS means one of three things: (a) shield all of Instagram and redirect the user to a web wrapper with Reels hidden, (b) a Safari Web Extension or content blocker that hides Reels/Shorts in Safari, or (c) time-limited friction that unlocks the app. Market iOS as "whole-app plus web clean feed," not as surgical in-app blocking.
- Treat DeviceActivity threshold events as unreliable. Use schedule-based intervals and reconcile state when the app opens. Keep authoritative block state in the App Group, not in ManagedSettingsStore.
- Request every extension entitlement (shield config, shield action, monitor, report) early and in parallel. Allow weeks.
- Shortcuts automations need many manual setup steps (one per app), and users can turn them off easily. They work for friction or interventions, not for enforcement.

### Gaps
- Apple's official WWDC 2026 session notes and iOS 27 API diffs could not be retrieved. Confirm whether anything new exists (for example, a web-domain or per-app usage API).
- Opal's exact implementation was not found. It is presumed to use the Screen Time API, but this is unverified.
- No Apple acknowledgment or fix for the iOS 26.2/26.3 DeviceActivity bugs was found.
- No App Store rejection or removal cases for blockers in 2025–2026 were found.

---

## 3. Desktop and browser: Chrome MV3, macOS/Windows approaches

### Takeaway
Browser extensions remain the most precise and lowest-risk way to block Shorts and Reels on desktop. declarativeNetRequest (DNR) handles URL- and domain-level blocks, and a content script hides DOM elements. Native desktop blockers such as Cold Turkey and Freedom rely on hosts-file or system-level changes plus companion browser extensions and uninstall or anti-quit protections.

### Cited Findings
- DNR blocks or modifies requests with declarative rules and does not read request content. It cannot run content scripts or hide elements, so element hiding needs a content script. — [Chrome declarativeNetRequest docs](https://developer.chrome.com/docs/extensions/reference/api/declarativeNetRequest)
- Limits: up to 50 static rulesets, of which 10 can be enabled at once. Dynamic and session rules are capped at 5,000 combined; this figure appeared on an MV2-path page, so verify it. "Safe" rules (block, allow, allowAllRequests, upgradeScheme) get a higher limit of 30,000. Each regex rule must compile to less than 2 KB. — [Chrome DNR docs](https://developer.chrome.com/docs/extensions/reference/api/declarativeNetRequest); [Chrome blog: improvements to content filtering in MV3](https://developer.chrome.com/blog/improvements-to-content-filtering-in-manifest-v3.md.txt)
- Firefox supports `declarativeNetRequest` as well. — [MDN declarativeNetRequest](https://developer.mozilla.org/docs/Mozilla/Add-ons/WebExtensions/API/declarativeNetRequest)
- Cold Turkey (Windows) blocks by editing the hosts file and runs extra services to keep blocks active. It cannot simply be uninstalled during a block. Its guide suggests making the hosts file read-only. — [Windows Digitals](https://www.windowsdigitals.com/uninstall-cold-turkey-during-a-block/); [Cold Turkey user guide](https://getcoldturkey.com/support/user-guide)
- Cold Turkey reportedly prevents direct uninstalls on Mac and Windows once a block starts. Its browser extensions keep blocking on their own. — [Cisdem review](https://cisdem.com/resource/cold-turkey-app-review.html)
- Freedom (Windows) has built-in uninstall prevention during sessions. On Mac, it offers "Start at Login" and "Block Activity Monitor" to stop users quitting mid-session. — [Freedom: prevent uninstallation](https://support.freedom.to/en/articles/6116638-how-to-prevent-uninstallation-of-the-freedom-app)

### Inferences
- YouTube is a single-page app, so DNR URL rules on `/shorts/` catch navigations but not the Shorts shelf on the home feed. A MutationObserver content script with CSS selectors is needed. Those selectors break when YouTube changes its markup, so ship selector lists as remotely updatable config (data, not code), which Chrome Web Store policy permits.
- [background, unverified] On macOS, a durable modern approach is a Network Extension (content filter or DNS proxy) plus the Screen Time / FamilyControls APIs that macOS also exposes. On Windows, the usual approach is WFP/hosts plus a service. Both should be paired with browser extensions for in-page hiding.

### Gaps
- No sourced information on Cold Turkey's or Freedom's macOS Network Extension or proxy internals.
- No data on how often YouTube or Instagram web DOM changes break extension selectors.

---

## 4. Risks: UI churn breaking detection, store rejections, battery and performance

### Takeaway
Breakage from Instagram and YouTube updates is real and recurring. Open-source blockers document repeated outages from view-ID renames and accessibility-flag changes, with fixes shipped as code changes. Store risk is driven mainly by policy tightening (Play accessibility in 2026, AAPM) and by slow iOS entitlement approval, rather than by documented takedowns.

### Cited Findings
- LifeIsShort issue #18: after an Instagram update, the accessibility service saw no view IDs (`visibleSourceIds=[]`, `reelsViewer=false`), so Reels went unblocked. PR #19, merged October 4, 2026, fixed it by restoring `flagReportViewIds` and the selected/clicked view events. — [Issue #18](https://github.com/Feuscel/LifeIsShort/issues/18); [PR #19](https://github.com/Feuscel/LifeIsShort/pull/19)
- reelblock's README says Reels came back after an Instagram update ("Instagram maybe changed its view ID"). — [reelblock](https://github.com/SuzalShrestha/reelblock)
- insta-block warns that blocking stops when Instagram renames view IDs. — [insta-block](https://github.com/rustyisacat/insta-block)
- App-Block PR #35 fixed a Reels-block "blink" (v0.10.3) and the feed over-blocking that fix exposed (v0.10.4). Fixes cause regressions in both directions: false negatives and false positives. — [App-Block PR #35](https://github.com/skytheacademic/App-Block/pull/35)
- Some OEM updates (HyperOS) silently disable accessibility services. — [CleanBrowsing](https://cleanbrowsing.org/support/mobile/disable-restricted-settings-android)
- Android 15 caps dataSync foreground services at 6h/24h, and blocker foreground services must use a correct type. A wrong type can lead to rejection. — [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15); [Play FGS requirements](https://support.google.com/googleplay/android-developer/answer/13392821)

### Inferences
- Expect detection to break several times a year per target app. Mitigations: remote selector and heuristic config, multiple redundant signals, on-device self-test (for example, check that the detector recognizes the Reels tab after each target-app update by watching the package version), and user-reported "it's not blocking" telemetry.
- Battery: AccessibilityService event handling filtered to target packages (`packageNames` in the service config, as in block-instagram-reels issue #8) is cheap. Polling or capturing the screen is expensive.

### Gaps
- No quantified data was found on how often breakage happens, on battery impact, or on reviewer rejection rates for blockers.

---

## 5. On-device ML for private screen-content classification

### Takeaway
On-device image classifiers (MediaPipe/LiteRT, ML Kit custom models) can classify screen frames, and at least one public phone-screen classifier exists. Getting frames requires MediaProjection (Android), which shows a persistent consent prompt and recording indicator, or accessibility screenshots, which are more policy-exposed. On iOS, no third-party app can capture other apps' screens. ML is useful as a fallback signal on Android, not as the main mechanism.

### Cited Findings
- The MediaPipe Image Classifier for Android supports still images, video files, and live streams with `.tflite` models. — [MediaPipe Image Classifier (Android)](https://developers.google.com/edge/mediapipe/solutions/vision/image_classifier/android)
- ML Kit supports custom classification models on Android. — [ML Kit custom models](https://developers.google.com/ml-kit/vision/object-detection/custom-models/android?hl=en)
- Android's AI overview recommends on-device models for offline or privacy-sensitive features, but suggests cloud models for video-input tasks. — [Android AI overview](https://developer.android.com/ai/overview)
- A MobileNetV4-based "phone-screen-classifier" on Hugging Face outputs a screen category and a content-safety category. Its license and intended use need checking. — [Hugging Face: yapwithai/phone-screen-classifier](https://huggingface.co/yapwithai/phone-screen-classifier)

### Inferences
- The cheapest private "ML" is classifying the accessibility node tree (text, class names, layout features) with a small on-device model, not pixels. It survives view-ID renames better than hard-coded IDs, with no screen capture and minimal battery cost. Pixel classification through MediaProjection is a heavier fallback.
- [background, unverified] Android `AccessibilityService.takeScreenshot()` (API 30+) exists but adds policy scrutiny. MediaProjection consent is required for every session on recent Android versions.

### Gaps
- No sources were found on MediaProjection policy constraints for continuous capture, or on shipped apps using ML for Reels/Shorts detection.

---

## 6. Recommended architecture for durability and cross-device sync

### Takeaway
Build a layered, capability-negotiating system. A shared cloud "policy" (rules, schedules, focus sessions) syncs to platform-specific enforcement adapters, each using the most durable mechanism its platform allows, with graceful fallback. Treat Android accessibility-based in-app blocking as a premium feature that could be taken away, not as the foundation.

### Cited Findings
(The architecture is synthesized from the sourced constraints above. Specific supporting facts:)
- iOS enforcement must go through tokens. The shield extension cannot open URLs, and the store cannot be reliably read back, so local mirrored state is needed. — [DEV (Expo guide)](https://dev.to/yarmolenkod/building-an-ios-app-blocker-with-the-screen-time-api-familycontrols-in-expo-what-the-docs-dont-2emi)
- Android accessibility can be revoked by AAPM or OEM updates, so fallbacks are needed. — [Android Authority](https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860/); [CleanBrowsing](https://cleanbrowsing.org/support/mobile/disable-restricted-settings-android)
- A local VpnService DNS filter is a Play-declared, viable domain-level layer. — [Loop Lock](https://play.google.com/store/apps/details?id=com.fys.looplock); [Play VpnService policy](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en)

### Inferences
- **Policy layer (cloud, platform-neutral):** user rules as abstract intents ("block short-form video," "limit Instagram to 20 min," "focus session 9–11"). Sync through any backend (for example Supabase or Firestore, with end-to-end encryption of schedules optional). Schedules should be computed on the device so they work offline.
- **Adapters:**
  - Android tier 1: AccessibilityService in-app detection, filtered by package, with remote selector config and node-tree heuristics.
  - Android tier 2: UsageStats plus overlay for whole-app blocking.
  - Android tier 3: local VpnService DNS blocking.
  - iOS: FamilyControls whole-app, category, and web-domain shields. Optional Safari Web Extension to hide Shorts/Reels on the web. Optional Shortcuts-based intervention.
  - Desktop: MV3 extension (DNR plus content-script selectors) for Chrome, Edge, and Firefox. Optional native helper (macOS Network Extension, Windows service) for app blocking.
- **App-specific iOS tokens are not portable.** ApplicationTokens are opaque and device-specific, so iOS rules must be re-selected through FamilyActivityPicker on each device. Cross-device sync can carry categories, schedules, and web domains, but not concrete app selections. [background, unverified — check that tokens are not shareable across devices]
- **Remote config and kill switches:** ship detectors as versioned JSON (selectors, heuristics) keyed by target-app version. Monitor detection health, and notify users when detection degrades instead of failing silently. This is critical for trust with ADHD users.
- **Policy hygiene:** a prominent-disclosure flow for Android accessibility, an honest Play declaration (no `isAccessibilityTool`), correct `specialUse` foreground service justification, a minimal permission set, no accessibility data leaving the device (as ScreenZen promises), and a clear privacy policy.
- **Durability ranking (most to least durable):** browser extension DNR > iOS FamilyControls whole-app > Android UsageStats + overlay > Android VpnService DNS > Android Accessibility in-app detection > iOS web-wrapper / Shortcuts tricks.

### Gaps
- No primary-source guidance was found on cross-device sync of iOS Screen Time tokens, or on Apple's position on in-app browser wrappers for third-party services (a possible App Store guideline 4.x / IP risk).
