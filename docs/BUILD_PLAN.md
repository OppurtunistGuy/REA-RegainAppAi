# Steady build plan (12 phases)

Working name: **Steady**. Strategy, evidence and sources: `reports/ADHD focus app long term strategy.md`.
Product flow: `prototype/steady-prototype.html`.

Principles that every phase follows:
- Strict mode stays free. Paid tiers add depth and scale.
- Screen contents never leave the phone. No ad SDKs.
- Wellness and learning claims only, never "treats ADHD".
- Clean-room build: public docs and our own spec only, no decompiling Regain or any competitor.
- Each phase ends with an exit check. Don't start the next phase until it passes.

Rough timeline: about 18–24 months with a 2–4 person team. Durations assume part-time founders can stretch them.

| # | Phase | Weeks | Ships to |
|---|---|---|---|
| 1 | Foundation and rules engine | 3 | Internal |
| 2 | Android shell and setup | 4 | Internal |
| 3 | Scheduled whole-app blocking | 4 | Internal |
| 4 | Intercept screen | 3 | Closed beta, 30–50 users |
| 5 | Reels and Shorts hiding | 5 | Closed beta |
| 6 | Strict mode and key holder | 5 | Closed beta, 200 users |
| 7 | Today plan and focus block | 4 | Open beta (Play testing track) |
| 8 | Learning layer | 6 | Open beta |
| 9 | Insights and public launch | 4 | Play Store, India |
| 10 | Plus tier and billing | 4 | Play Store, India + US |
| 11 | Chrome extension and iOS | 10 | Chrome Web Store, App Store |
| 12 | Institutes and outcome study | 12+ | B2B pilots |

---

## Phase 1: Foundation and rules engine
**Goal:** one tested, platform-neutral brain that every app (Android, iOS, Chrome) follows.
- Repo layout, CI running tests on every push.
- `core/` Kotlin module: schedules, blocked surfaces, block decisions, intercept choices, unlock cost, key holder rules.
- Product spec of each screen, written from our prototype (clean-room record).
- 15–20 user interviews (ADHD adults, JEE/NEET students, parents) using the prototype.

**Exit:** core tests pass in CI; at least 10 interviews confirm the scheduled strict mode + key holder idea.

## Phase 2: Android shell and setup
**Goal:** installable app that completes setup and stores the plan.
- Kotlin + Jetpack Compose app, Room database, `core` as a dependency.
- Setup flow: pick distractions, schedules, key holder.
- Permission screens with plain-language disclosure (usage access, overlay, notifications).

**Exit:** a new user finishes setup in under 2 minutes on a low-end Android phone (2 GB RAM).

## Phase 3: Scheduled whole-app blocking
**Goal:** blocks start by themselves at the scheduled time.
- Foreground service, UsageStats to detect the app in front, overlay shield.
- Schedules start blocks with no tap from the user.
- Battery and memory budget measured on 3 real devices.

**Exit:** blocks start on time 99% of the time over a 7-day test; battery cost under 3% per day.

## Phase 4: Intercept screen
**Goal:** the evidence-backed core: pause, ask, offer easy exits.
- 5-second breath, then three exits: close app (biggest), 60-second swap (placeholder card for now), messages only.
- Counts of urges caught and opens closed, stored on the phone.

**Exit (closed beta):** at least 30% of intercepts end with "close app"; testers keep it installed for 2 weeks.

## Phase 5: Reels and Shorts hiding
**Goal:** hide just the short-video surfaces while the rest of the app works.
- Accessibility service that reads the screen layout on the phone only.
- Detectors for Instagram Reels, YouTube Shorts, Snapchat Spotlight shipped as remote config.
- Health check: if a detector fails, fall back to whole-app block and tell the user.
- Play Console Accessibility declaration and prominent disclosure.

**Exit:** detectors work on current app versions; a broken detector is fixed in under 24 hours without an app release.

## Phase 6: Strict mode and key holder
**Goal:** a commitment device that is hard to undo but never traps anyone.
- Strict mode: can't stop the block or uninstall during it (device admin, disclosed).
- Emergency unlock: 30-second wait + typed reason.
- Key holder pairing (friend or parent) with a small backend (auth, push, pairing codes). Key holder sees bypasses and unlock requests only.

**Exit:** fewer than 5% of strict blocks end in an unlock; no tester reports feeling trapped (safety exit always works).

## Phase 7: Today plan and focus block
**Goal:** help people start, not just stop.
- Energy check-in sets block length (15 / 25 / 40 min).
- Tasks with a one-minute first step; AI breakdown of a task into steps (LLM API, opt-in).
- Visual time ring, current step, blocked list.

**Exit (open beta):** at least 40% of weekly users start a block from the Today screen.

## Phase 8: Learning layer
**Goal:** turn distraction into study reps.
- Notes to flashcards (typed, photo of notes, or AI-generated from a chapter), user edits before saving.
- 60-second swap uses real cards. Three-line recall after each block.
- Spaced review scheduler (FSRS-style). Starter decks for NCERT Physics, Chemistry, Biology.

**Exit:** at least 25% of intercepts that pick "swap" finish the card; weekly review completion over 50% for active learners.

## Phase 9: Insights and public launch
**Goal:** launch in India on the Play Store, free.
- Weekly flexible goal (no streaks), focus time, urges caught.
- Privacy screen: "what Steady sees and what never leaves your phone".
- Store listing, ASO in English and Hindi, study-creator launch.

**Exit:** 10,000 installs; 30-day retention above 15% (category median is about 3%).

## Phase 10: Plus tier and billing
**Goal:** revenue without paywalling the part that works.
- Play Billing with UPI Autopay; India ₹149/mo or ₹999/yr; US $4.99/mo or $39/yr (test these).
- Plus: unlimited schedules and devices, AI breakdown, notes to cards, Chrome blocking.
- One-tap cancel, renewal reminder 3 days ahead, clear refund path.

**Exit:** 2–4% of 35-day users pay; refund rate under 5%.

## Phase 11: Chrome extension and iOS
**Goal:** close the "I'll just use my laptop" loophole and reach US users.
- Chrome extension (Manifest V3): hide Shorts shelf and Reels on web, site blocks on the same schedules.
- iOS app: Screen Time API (FamilyControls, ManagedSettings, DeviceActivity) for whole-app blocks; Shortcuts automation for the breath screen; Safari extension for web Shorts. Apply for entitlements at the start of this phase.
- Sync of schedules and key holder across devices.

**Exit:** iOS app approved; 20% of Plus users connect a second device.

## Phase 12: Institutes and outcome study
**Goal:** the long-term moat: contracts and evidence.
- Coaching institute and school licences: batch schedules pushed by the institute, aggregate reports only, parent key holder setup.
- Parental consent flow for under-18s (India DPDP rules, US COPPA).
- Registered outcome study with a university partner (study time, exam scores, wellbeing).

**Exit:** 3 paying institutes; study protocol registered and enrolling.

---

## Later, only if earned
- Co-study rooms (body doubling) once there is a community to fill them.
- Germany DiGA track if the outcome study is strong.
- More languages and exam tracks (UPSC, CUET, SAT).
