# Store listings

All copy follows [`wellness-claims-guide.md`](wellness-claims-guide.md). "Steady" is a working
name. Run a trademark search and confirm the final name **before** the first upload, because the
application id `app.steady` (`android/app/build.gradle.kts`) can never be changed after it is
uploaded (see the launch checklist).

Trademarks: name Instagram, YouTube, Snapchat and Facebook only in the description, factually,
to say what Steady works with. Never use them in the title, icon or screenshots' headline text, and
never use their logos. Say "not affiliated with" once.

Play limits: title 30, short description 80, full description 4,000 characters. No emoji, ALL
CAPS, "#1", "best", price or promo text in the title or short description.

## 1. Google Play: India (en-IN; Hindi listing later)

**Title (29):** `Steady: Study Focus & Blocker`

**Short description (79):** `Hide Reels and Shorts in study hours. Strict mode is free and starts by itself.`

**Full description:**

```
Steady is a study timer and focus blocker for students preparing for boards, JEE, NEET, CUET or college exams.

Most blockers only work if you remember to start them. Steady starts strict mode on its own, on the schedule you pick once. Coaching 4–7 pm, night study 9–11 pm: the block just begins.

HIDES THE SCROLL, KEEPS THE APP
During your blocks, Steady hides Reels in Instagram, Shorts in YouTube, Spotlight in Snapchat and Reels in Facebook. Messages, lecture videos and the rest of each app keep working. You can also block whole apps, like games, for the length of a block.

A PAUSE INSTEAD OF A FIGHT
When you open a short-video section, Steady shows a 5-second breath and three choices: close the app, do a 60-second flashcard swap, or go back to just messages. Closing is the biggest button and is there right away.

STRICT MODE IS FREE
Strict blocks end on time. Emergency unlock asks for a short wait and a reason. If you choose a key holder, a friend or a parent approves it. They see only your unlock requests, never your screen or messages.

STUDY, NOT JUST BLOCKING
• Today: check your energy, see your blocks for the day, pick one tiny first step
• Focus: a shrinking ring shows the time left
• Recall: write three lines after a block and they become spaced review cards
• Insights: focus minutes and urges caught per week. No streaks to lose.

PRIVATE BY DESIGN
Steady uses Android's Accessibility service only during your blocks, only to tell which section is open. Your screen, messages and what you watch never leave your phone. No ads, no ad trackers. An account is optional, and you need one only to sync with Chrome or use a key holder.

FREE
Strict mode, 2 schedules, Reels and Shorts hiding, breath screen and swap, 1 key holder.

STEADY PLUS (₹149/month or ₹999/year, UPI supported)
[List only features that have shipped; see B10. For example: Unlimited schedules.]
Cancel in one tap on Google Play.

Designed with ADHD brains in mind, and for anyone who finds it hard to start. Steady is a focus and study-habits tool, not a medical product.

Steady is not affiliated with Instagram, YouTube, Snapchat or Facebook.
```

## 2. Google Play: US (en-US)

**Title (29):** `Steady: Focus & Study Blocker`

**Short description (76):** `Strict mode that starts on schedule. Hides Reels and Shorts, keeps messages.`

**Full description:**

```
Steady is a focus and study tool designed with ADHD brains in mind, for anyone who finds it hard to start, and harder to stop scrolling.

STRICT MODE THAT STARTS ITSELF
Pick your study or work blocks once. Steady starts strict mode on schedule, so you never have to remember to begin a session.

HIDE THE FEED, KEEP THE APP
During blocks, Steady hides Instagram Reels, YouTube Shorts, Snapchat Spotlight and Facebook Reels. DMs, search and regular videos keep working. You can also block whole apps for the length of a block.

A PAUSE WITH AN EASY WAY OUT
Opening a short-video section brings up a 5-second breath and three choices: close the app, a 60-second flashcard swap, or just messages. The idea comes from research on short delays and easy exits in phone use (studies of general phone users, not of Steady).

COSTLY TO OVERRIDE, NOT IMPOSSIBLE
Strict blocks end on time. Emergency unlock asks for a wait and a reason. Add a key holder (a friend, partner or parent) to approve unlocks. They see only the requests, never your screen.

BUILT FOR STUDY
• Today: energy check-in, your day's blocks, one tiny first step
• Focus: a visual ring shows the time left
• Recall: three lines after a block become spaced review cards
• Insights: focus minutes and urges caught per week. No streaks, no guilt.

PRIVATE BY DESIGN
Accessibility is used on your phone only, during your blocks, only to see which section is open. Screen contents, messages and watch history never leave your device. No ads, no ad SDKs, no data sales. An account is optional.

FREE: strict mode, 2 schedules, Reels and Shorts hiding, breath screen and swap, 1 key holder.
STEADY PLUS ($4.99/month or $39/year): [shipped features only, B10]. Cancel in one tap on Google Play; we remind you before renewal.

Steady is a wellness and productivity app. It is not a medical device and does not diagnose or treat any condition.

Not affiliated with Instagram, YouTube, Snapchat or Facebook.
```

Prices come from `prototype/steady-prototype.html` (Plus tier) and `paywall_default_*` in
`strings_account.xml`. They are proposals to test. The app shows Play's formatted price, so the
listing text must match the Play Console price for each country.

**Do not publish "Strict blocks end on time" until B13 is fixed.** Today a strict block can be
ended by editing the plan inside Steady (QA S-08). Until then, use "Strict blocks are set ahead of
time and are costly to undo."

## 3. Feature bullets (website, feature graphic, ASO notes)

- Strict mode starts on your schedule, and it is free
- Hides Reels and Shorts, keeps messages and lectures
- 5-second breath, then an easy way out
- Turn an urge into one flashcard
- A friend or parent approves emergency unlocks
- Your screen never leaves your phone
- No streaks to lose

ASO keywords to work into the description naturally (no keyword lists): study timer, focus timer,
app blocker, reels blocker, shorts blocker, JEE / NEET study (India), ADHD focus (US, description
only).

## 4. Screenshot plan

Phone screenshots 1080×1920 (9:16), 8 frames, light theme first, plus a dark-theme set for
testing. Use sample data that matches the prototype: student "Riya", NCERT physics, schedules
"Coaching 4–7 pm Mon–Fri" and "Night study 9–11 pm daily". Capture from the real app (release
build), not from the HTML prototype, once the screens match. The headline text sits above the
device frame.

| # | Screen (prototype id → app route) | Headline | Notes |
|---|-----------------------------------|----------|-------|
| 1 | `setup` → `Setup` | Strict mode that starts itself | Distractions picked, the two schedule presets, key holder |
| 2 | `intercept` → overlay `InterceptContent` | A pause, not a fight | Breath done, three exits visible; "Close Instagram" button. Do not show the Instagram UI behind it |
| 3 | `focus` → `Focus` | Costly to override, on purpose | Ring, "Coaching block · strict", locked list, Emergency unlock |
| 4 | `swap` → `Swap` | Turn an urge into a flashcard | Physics card, 60 s timer |
| 5 | `today` → `Today` | One tiny first step | Energy check-in, day strip, first step |
| 6 | `recap` → `Recap` | Three lines become review cards | "What stuck? Three lines.", week goal, "No streak to lose" |
| 7 | `partner` → `Partner` | A key holder, not a spy | Unlock request card plus the "Never shared" card |
| 8 | (no prototype frame) → `Disclosure` or Settings › Privacy | Your screen stays on your phone | The three privacy cards. Shows reviewers that our disclosure is consistent |

Feature graphic 1024×500: the wordmark plus "Strict mode that starts itself". No third-party logos.
Optional promo video: reuse the demo video storyboard (`play-accessibility-declaration.md` §6), cut
to 30 s, with no third-party UI.

## 5. Chrome Web Store

**Name:** Steady

**Summary (manifest `description`, 100/132):** `Hides YouTube Shorts and Instagram Reels and pauses listed sites during your scheduled study blocks.`

**Category:** Productivity (Workflow & planning). **Language:** English.

**Single purpose statement:**

> Steady enforces the study schedule the user set in the Steady app. During those blocks it hides
> YouTube Shorts and Instagram Reels and pauses websites the user listed, with a short breath page
> that offers to close the tab.

**Detailed description:**

```
Steady for Chrome keeps the study blocks you set in the Steady phone app working on your laptop.

During a block:
• YouTube Shorts and Instagram Reels links are hidden and send you to a short breath page
• Websites you listed are paused
• The breath page offers to close the tab, a 60-second swap, or an unlock after a short wait and a reason

Your plan syncs from the Steady app when you sign in with the same email or phone. If you're offline, the last plan keeps working.

Privacy: the extension does not send page addresses, page contents or browsing history anywhere. It only downloads your plan and the blocking rules.

Not affiliated with YouTube or Instagram.
```

### Permission justifications (from `extension/static/manifest.json`)

| Permission | Justification to paste | Status |
|------------|------------------------|--------|
| `storage` | Stores the user's settings, sign-in token, the last synced plan, blocking rules and local unlock timers in `chrome.storage.local`, so blocks work offline. | OK |
| `alarms` | Re-checks the schedule every minute and at exact block start and end times, and syncs the plan every 15 minutes, while the service worker is asleep. | OK |
| `declarativeNetRequest` | Pauses the websites the user listed (main frame only) during scheduled blocks, and redirects YouTube `/shorts/` and Instagram `/reels/` URLs to the extension's breath page. Rules exist only while a block is active. | OK |
| Host `*://*.youtube.com/*` | A content script hides Shorts shelves and entry points during blocks. Host access is also needed for DNR to redirect `/shorts/` to the breath page. | OK |
| Host `*://*.instagram.com/*` | Same as above, for Reels. | OK |
| Host `https://api.steady.app/*` | The Steady API, used to download the user's plan and blocking rules. | **B14:** placeholder domain. Replace with the real production API host before submission |
| Host `http://localhost/*`, `http://127.0.0.1/*` | Development only | **B14:** remove from the store build. Reviewers reject unexplained broad or cleartext hosts |
| Optional host `<all_urls>` | Requested **per domain at runtime**, only for sites the user adds to their block list, so those sites show the breath page instead of Chrome's error page. Never requested at install. | OK. Say clearly that only the listed domains are requested |
| `web_accessible_resources` (`interstitial.*`, `tokens.css`, matches `<all_urls>`) | The breath page must load after a DNR redirect from a blocked site. | Not a permission, but explain it if asked. Consider narrowing `matches` to the YouTube and Instagram origins plus listed sites |

Remote code: **No.** The extension downloads detector data (CSS selectors as JSON) from the API.
This is data, not executable code. Say so in the "Are you using remote code?" answer.

The Options page also lets the user type any API base URL (extension README). Hide it or lock it to
the production host in the store build (part of B14).

Privacy practices tab: see [`play-data-safety.md`](play-data-safety.md) §4. Privacy policy URL: the
same as Play.
