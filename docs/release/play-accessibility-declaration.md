# Play Console: AccessibilityService declaration

Status: draft for the first internal-testing release. It is written against the code at Phase 12c.
Blocking issues use the ids from [`launch-checklist.md`](launch-checklist.md#blocking-issues-policy-vs-code).

Policy references (check the live pages before you submit):
- Play Policy Center, "Use of the AccessibilityService API" (Permissions and APIs that Access Sensitive Information)
- Play Console Help, "Use of the AccessibilityService API" declaration form (answer 10964491)
- Play User Data policy, "Prominent Disclosure and Consent Requirement"

## 1. Summary for the reviewer

Steady is a study and focus app. During study blocks that the user schedules, it hides the
short-video sections inside four apps: Instagram Reels, YouTube Shorts, Snapchat Spotlight and
Facebook Reels. Messages and the rest of each app keep working. Steady can also send the user
Home from whole apps the user chose to block (for example a game). Hiding one section inside
another app is the app's core function. No other Android API can do it: UsageStats and app
blocking only see the whole app, not which tab is open. Steady does this through the
AccessibilityService API.

Steady is **not** an accessibility tool. It does not set `android:isAccessibilityTool="true"`
(see `android/app/src/main/res/xml/accessibility_service_config.xml`), and it must never set it.
Play reserves that flag for apps whose main purpose is to help people with disabilities.

## 2. What the code actually does

| Aspect | Implementation | File |
|--------|----------------|------|
| Service | `.enforcement.SteadyAccessibilityService`, `exported=false`, guarded by `BIND_ACCESSIBILITY_SERVICE`, label "Steady focus helper" | `android/app/src/main/AndroidManifest.xml`, `res/values/strings.xml` |
| Packages observed | Static list: `com.instagram.android`, `com.google.android.youtube`, `com.snapchat.android`, `com.facebook.katana`. At runtime it is replaced with the detector packages plus any `app:<pkg>` the user blocks (`observedPackages()`, written to `serviceInfo.packageNames`) | `res/xml/accessibility_service_config.xml`, `enforcement/EnforcementDecider.kt`, `enforcement/SteadyAccessibilityService.kt` (`refreshObservedPackages`) |
| Events | `typeWindowStateChanged`, `typeWindowContentChanged`, `typeViewScrolled`, debounced to 250 ms | config XML, `SteadyAccessibilityService.onAccessibilityEvent` |
| Flags | `canRetrieveWindowContent=true`, `flagReportViewIds`, `flagRetrieveInteractiveWindows`, `canPerformGestures=false` | config XML |
| When the screen is read | Only when the rules say something about the foreground package is blocked **right now** (`EnforcementDecider.anyBlocked`). Outside blocks, and for packages with no active rule, `rootInActiveWindow` is released without being walked. | `SteadyAccessibilityService.evaluate` |
| What is read | The node tree of the active window, up to 4,000 nodes, walked lazily. Properties copied in memory: view id, class name, **text**, **content description** and selected state. They are matched against `shared/detectors/detectors.v1.json`, which uses view ids and `descContains: "Reels"` / `"Shorts"` / `"Spotlight"`. | `enforcement/NodeTree.kt`, `android/core/detect` |
| Actions taken | `performGlobalAction(GLOBAL_ACTION_BACK)` to leave a hidden section, or `GLOBAL_ACTION_HOME` for a whole-app block or an unhealthy detector. At most one action per package every 1.5 s. Then a full-screen `TYPE_ACCESSIBILITY_OVERLAY` intercept with three exits (Close, 60-second swap, Just messages). | `EnforcementDecider.kt`, `InterceptOverlay.kt` |
| What is stored | One row per intercept: surface id (for example `instagram.reels`) and a timestamp, in the local Room table `intercept_events`. Nothing from the tree is stored. | `SessionRepository.recordIntercept`, `data/db/Entities.kt` |
| What leaves the device | Nothing from the accessibility tree. When the user signs in, finished focus sessions are uploaded as aggregates: start, end, intercept count and unlock count. | `remote/ApiModels.kt` (`SessionBody`), `remote/SessionUploader.kt` |
| Backend guard | Every request body is a `.strict()` zod schema, so unknown fields are rejected and screen content cannot be stored | `backend/src/schemas.ts` |

## 3. Declaration form answers

The Play Console asks these questions under App content → Sensitive permissions and APIs →
Accessibility API. Paste the answers below and change the wording only if the code changes.

**Does your app use the AccessibilityService API?** Yes.

**Is your app an accessibility tool (isAccessibilityTool)?** No.

**Describe the core functionality of your app that uses the Accessibility API.**

> Steady is a study and focus app. The user sets study blocks on a schedule, for example
> "Coaching 4–7 pm Mon–Fri". During those blocks, Steady's AccessibilityService checks which
> section of Instagram, YouTube, Snapchat or Facebook is open. When the Reels, Shorts, Spotlight
> or Facebook Reels section opens, it presses Back once to leave that section and shows a short
> pause screen. The user can then close the app, do a 60-second flashcard, or go back to messages.
> If the user chose to block a whole app, Steady presses Home instead. Hiding one section inside
> another app, while messages and the rest of the app keep working, is the main feature of
> Steady. No other Android API can do it: UsageStats and app blocking only see the whole app, not
> which section is open.

**What data does the API access, and how is it used?**

> Only while a study block is active, and only for Instagram, YouTube, Snapchat, Facebook and any
> app the user chose to block, Steady reads the window's view hierarchy in memory: view ids,
> class names, selected state and on-screen labels (text and content descriptions). It matches
> them against a list of known section identifiers, such as the Reels tab id or a selected
> "Shorts" label. The tree is dropped as soon as the check finishes. Steady keeps only the name
> of the caught section and the time, on the device, to show the user a daily count.

**Is any data collected or shared via the API?**

> No data read through the Accessibility API is collected, stored, or sent off the device. The
> on-device intercept count (section name and time) is used only for the user's own insights. If
> the user creates an optional account, only the daily totals (number of intercepts per focus
> session) are synced, not the screen contents. This is declared in the Data safety form.

**Does the app change user settings, prevent uninstall, or stop the user turning the service off?**

> No. The service only presses Back or Home, and only during blocks the user scheduled. The user
> can turn the service off in Settings › Accessibility, or uninstall the app, at any time.

**Link to the prominent disclosure / video.** See section 6. Upload the video as unlisted on
YouTube or Google Drive.

## 4. Prominent disclosure as implemented

Screen: `android/app/src/main/java/app/steady/feature/disclosure/DisclosureScreen.kt`, route
`Disclosure`. Strings: `android/app/src/main/res/values/strings_enforcement.xml`.

| Element | Text (exact) |
|---------|--------------|
| Label | Before you turn it on |
| Title | How Steady uses Accessibility |
| Intro | To hide Reels, Shorts, Spotlight and Facebook Reels during your study blocks, Steady needs Android's Accessibility permission. Here is exactly what that means. |
| Card 1: What Steady looks at | The screen layout of Instagram, YouTube, Snapchat, Facebook and any apps you chose to block, and only while one of your focus blocks is running. It checks which section is open (for example, whether the Reels tab is showing) so it can step back out of it and show a short pause screen. |
| Card 2: What Steady never collects or sends | Your screen contents, your messages, what you watch or type. None of it is stored or shared. Steady only keeps a count of when a short-video section was caught (which section and the time), to show you how your day went. |
| Card 3: On your phone only | All of this runs on your device. Nothing from your screen goes to Steady's servers or anyone else. You can turn it off any time in Settings > Accessibility. |
| Steps | In Settings, open "Installed apps" or "Downloaded apps", tap Steady focus helper and switch it on. |
| Consent button (affirmative) | **Turn on in Settings**, which opens `Settings.ACTION_ACCESSIBILITY_SETTINGS` |
| Decline | **Not now**, which goes back without opening Settings |
| When enabled | Pill "Focus helper is on", text "It hides short-video sections only during your blocks. You can turn it off any time in Settings > Accessibility.", buttons Done / Open Accessibility settings |

System description shown by Android in Settings › Accessibility (`enforcement_a11y_description`):

> Steady uses the AccessibilityService API to help you keep the study plan you set. During your
> focus blocks it looks at the screen layout of Instagram, YouTube, Snapchat, Facebook and any apps
> you chose to block, only to notice when Reels, Shorts, Spotlight or Facebook Reels open. It then
> steps back out of that section and shows a short pause screen, so messages and the rest of the
> app keep working. This all happens on your phone. Steady does not collect, store or share your
> screen contents, your messages or what you watch.

### Proposed disclosure wording fix (for B4)

Replace `disclosure_reads_body` with:

> The screen of Instagram, YouTube, Snapchat, Facebook and any apps you chose to block, and only
> while one of your focus blocks is running. Steady reads the layout and on-screen labels (like a
> tab called "Reels") to tell which section is open. If it is a short-video section, Steady presses
> Back once and shows a short pause screen. For an app you blocked completely, it goes to your Home
> screen instead.

Make the same two points (labels are read, and Home is pressed for whole-app blocks) in
`enforcement_a11y_description`. Delete the unused `accessibility_service_description` in
`res/values/strings.xml`. It is out of date and does not mention blocked apps.

## 5. Policy checklist

| # | Requirement (Play Accessibility API + User Data policy) | Status | Evidence / issue |
|---|----------------------------------------------------------|--------|------------------|
| 1 | Not declared as an accessibility tool unless the app's main purpose is disability support | Pass | No `isAccessibilityTool` in config XML |
| 2 | Play Console declaration filed, with core-functionality description and video | To do | Sections 3 and 6; launch checklist step 7 |
| 3 | Prominent disclosure **inside the app**, not only in the store listing or privacy policy | Pass | `DisclosureScreen.kt` |
| 4 | Disclosure shown in the normal flow of use; the user must not have to dig into a menu or Settings to find it | **Fail (B3)** | Only reachable from Settings › Accessibility disclosure (`SettingsScreen.kt:109`). Setup and Today never route to it, so users who never open Settings are never asked. Route Setup's last step (and Today, while the service is off) to `Disclosure`. Tracked in `docs/INTEGRATION_TODO.md` (Phase 6). |
| 5 | Disclosure states what data is accessed, how it is used and whether it is shared | **Partial (B4)** | It says "screen layout", but `NodeTree` reads text and content descriptions and the detectors match labels such as "Reels". It also does not say that Home is pressed for whole-app blocks. Fix the wording as in section 4. |
| 6 | Disclosure comes immediately before the permission request, and consent needs an affirmative tap; dismissing it or pressing Back is not consent | Pass | "Turn on in Settings" opens settings; "Not now" goes back. The service cannot be enabled from inside the app. |
| 7 | Request only the capabilities the feature needs | **Fail (B2)** | `flagRetrieveInteractiveWindows` is declared but never used. The code only calls `rootInActiveWindow`, not `getWindows()`. Remove the flag, or use it and justify it. |
| 8 | Must not change settings without permission, stop the user disabling or uninstalling any app or service, or get around Android privacy controls | Pass, but at risk | No device admin and no uninstall blocking. **Risk:** the plan accepts any `app:<pkg>` (`backend/src/schemas.ts` `SURFACE_ID`), so a synced or hand-edited plan could block `com.android.settings` or the package installer, which would stop the user turning the service off. Add a denylist (Settings, package installer, Play Store, launcher, Steady itself) in `observedPackages()` and in the backend schema. Do **not** add the "uninstall protection" that Regain uses: it is prohibited for apps that are not parental-control apps. |
| 9 | Must not use the API deceptively, or to autonomously plan and carry out actions or decisions | Pass | Actions are a fixed Back or Home, triggered only by rules the user wrote. Section 3 says this explicitly. |
| 10 | No remote call audio recording | Pass | Not used |
| 11 | No data from the API collected or shared without disclosure; Data safety form accurate | Pass | Nothing from the tree leaves the device; see `play-data-safety.md` |
| 12 | Privacy policy link in the store listing **and** inside the app | **Fail (B20)** | No privacy policy link anywhere in the app (`feature/settings/SettingsScreen.kt` links only to Play subscriptions). Add one under Settings › Privacy and on the disclosure screen. |
| 13 | Foreground service type declared and justified | To do | `FocusService` uses `specialUse` with subtype `fgs_special_use_subtype`. Needs the FGS declaration in the Play Console with its own video (launch checklist step 7). **Risk:** `FocusService` says enforcement does not depend on it, so a reviewer may ask why it needs a foreground service. Justify it as the user-visible countdown for the block they scheduled. |
| 14 | Android 17 Advanced Protection Mode revokes accessibility for non-tool apps | Known limit | Not a policy failure. The app must degrade without crashing: QA case A11Y-12. Per `FallbackLadder`, the BLOCK_DOMAIN fallback is not built yet. |

## 6. Demo video script (for the declaration and the FGS declaration)

Reviewers want one continuous screen recording (no cuts between consent and use) that shows the
disclosure, the consent, the feature working, and how to turn it off. Record on a Pixel with
Android 15 or 16, with the release build and a test Instagram account that has no real personal
data. Target length 90–150 s. Use the device's screen recorder with touches shown
(Developer options › Show taps).

| t (s) | Show | Narration / caption |
|-------|------|---------------------|
| 0–10 | Launch Steady from a fresh install. Setup: pick "Instagram Reels" and "YouTube Shorts", pick "Night study" schedule, key holder "Self". | "Steady hides short-video sections during study blocks the user schedules." |
| 10–30 | The disclosure screen opens (from the end of Setup once B3 is fixed). Scroll slowly through all three cards and hold for a moment on each. | "Before asking for Accessibility, Steady explains what it reads, that nothing leaves the phone, and how to turn it off." |
| 30–35 | Tap **Not now** once, to show that declining works, then reopen the disclosure. | "Declining is respected." |
| 35–55 | Tap **Turn on in Settings**. Android Accessibility settings open → Installed apps → Steady focus helper → read the system description → switch it on → accept the system dialog. Back to Steady: "Focus helper is on". | "The user turns the service on in Android settings." |
| 55–65 | For the recording, set a schedule that starts now (Setup › edit the schedule), or use a debug build with a block active. The ongoing "Night study · 1:12 left" notification appears. | "A block starts on its own. A quiet notification shows the time left (foreground service)." |
| 65–90 | Open Instagram → Home feed and DMs work → tap the Reels tab → Steady presses Back and the breath pause screen appears → tap "Just messages" → DMs open. | "Inside the block, Reels is hidden; messages keep working." |
| 90–105 | Open YouTube → search and play a lecture video (works) → tap Shorts → same intercept → tap "Close YouTube". | "Normal YouTube videos keep working. Only Shorts are hidden." |
| 105–115 | Open Steady → Insights or Recap: "urges caught" count. | "Only the count and time are kept, on the phone." |
| 115–130 | Settings › Accessibility › Steady focus helper → switch off. Open Instagram Reels: it plays. | "The user can turn it off at any time." |
| 130–140 | (FGS video only) Wait for the block to end, or shorten it: the notification disappears. | "The foreground service runs only for the length of the block." |

Do not show real people's messages, faces or usernames. Blur them afterwards if they appear.
Keep the raw recording out of the repo.

## 7. Before you submit

- [ ] B2, B3, B4 and B20 fixed and re-recorded in the video
- [ ] Settings/installer denylist added (row 8 risk)
- [ ] Declaration text above matches the shipped `detectors.v1.json` packages
- [ ] Video uploaded (unlisted), link pasted into both the Accessibility and FGS declarations
- [ ] Data safety form submitted in the same release as the declaration
