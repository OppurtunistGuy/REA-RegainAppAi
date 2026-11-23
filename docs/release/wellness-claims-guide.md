# Wellness and claims guide

Steady is a **wellness, focus and study-habits product**, not a medical device. The claims we make
decide that, not the code. In the US, the FDA General Wellness guidance (January 2026 update)
covers products whose intended use is wellness only and that are low risk, judged by how they are
promoted. EU MDCG 2019-11 Rev.1 and India's CDSCO software guidance (July 2026) both turn on
whether there is a medical intended purpose. One sentence like "reduces ADHD symptoms" can make
the app a regulated medical device in all three markets. Google Play's Misleading Claims and
Health policies, and consumer law (FTC Act s.5, India's Consumer Protection Act 2019 and its
misleading-ads guidelines), apply as well.

Source: `reports/ADHD focus app long term strategy.md` ("Regulation and privacy"), `docs/PHASES.md`
(definition of done: "Wellness wording only").

This guide applies to store listings, screenshots, the website, ads, creator briefs, press, push
copy, emails and in-app strings.

## 1. Rules

1. **Talk about habits, plans and tools, not conditions.** Say what the app does ("hides Reels
   during your study blocks"), not what it does to your brain or your ADHD.
2. **"ADHD" only as a design audience**, never as something we act on: say "designed with ADHD
   brains in mind" or "built for people who find it hard to start", never "for treating ADHD".
3. **No outcome promises.** Do not promise grades, ranks, symptom change or screen-time reductions.
4. **Research is about mechanisms, not about Steady.** Name the population ("in a study of adults
   using a phone delay app"), and never imply that Steady was tested.
5. **No fear or shame.** Say "associated with poorer attention", not "rots your brain". No guilt
   copy and no streak loss (the product has no streaks).
6. **Be accurate about strictness.** Strict mode can be bypassed (see `qa-test-plan.md` §4). Never
   say "impossible to bypass", "uninstall-proof" or "can't be undone".
7. **Paid features must exist.** Never advertise a Plus feature that has not shipped.

## 2. Allowed vs forbidden

| Allowed | Forbidden (do not use, even in ads or creator scripts) |
|---------|-----------------------------------------------------------|
| "Designed with ADHD brains in mind" | "Treats ADHD", "ADHD treatment", "ADHD therapy", "digital therapeutic" |
| "For focus and study habits" | "Reduces / relieves / manages ADHD symptoms", "improves attention span", "improves executive function" |
| "Helps you stick to the study plan you set" | "Clinically proven", "doctor recommended", "psychiatrist approved", "FDA cleared / approved" |
| "Hides Reels and Shorts during your study blocks" | "Alternative to medication", "works like a stimulant", "drug-free ADHD help" |
| "Strict mode starts on its own, on your schedule" | "Diagnose", "screen for ADHD", "ADHD test", any symptom questionnaire |
| "A 5-second pause and an easy way out" | "Cures phone / social media addiction", "detox your brain", "rewire your brain" |
| "Turn an urge into one flashcard" | "Rots your brain", "Reels destroy your attention" |
| "Built on research about delays and commitment devices (studies of general phone users)" | "Science-backed results", "proven to boost focus by X%", "48% higher retention" (untraceable) |
| "Spaced review of your own notes" | "Guaranteed better marks / rank / NEET score" |
| "A friend or parent approves emergency unlocks" | "Parents can monitor everything their child does" (also untrue: they see only unlock requests and summaries) |
| "Your screen never leaves your phone" | "We collect nothing" (untrue with an account) |
| "Cancel in one tap on Google Play" | "Free" for a feature that is paid; "lifetime" deals we cannot honour |
| "Strict mode stays free" | "Impossible to bypass", "uninstall-proof", "can't be undone" |

Grey zone: legal review needed before use.
- "Mental wellness", "less stress". Prefer "calmer study sessions" (an experience, not an outcome).
- Testimonials that mention diagnosis or medication. Do not publish them.
- Category choice: list under **Productivity** (or Education), not Medical. Health & Fitness is
  allowed for wellness apps but invites a closer look. Answer Play's Health apps declaration as not
  a medical or health-research app.
- The Play title must not contain "ADHD" (it reads as a condition claim and as keyword stuffing).
  Use it in the description only, as a design audience.

## 3. Review of shipped strings

Reviewed: `android/app/src/main/res/values/strings*.xml` (5 files), hard-coded copy in
`android/app/src/main/java/app/steady/feature/**`, the extension's `static/*.html`,
`static/manifest.json` and `src/ui`, `src/interstitial`.

**No string uses ADHD, symptom, treat, cure, therapy, diagnose, clinical, proven, science, brain,
addiction or detox.** (A grep for these words over `android/app/src/main` and `extension/` finds
only code identifiers.) The tone (no shame, no streaks) follows the guide.

Strings that cross the line on **accuracy or paid-feature honesty** (the forbidden rows on
strictness, monitoring and paid features):

| String / location | Problem | Fix |
|-------------------|---------|-----|
| `paywall_plus_4` "Task breakdown help" (`strings_account.xml`) | No task-breakdown feature exists; `Features.TASK_BREAKDOWN` gates nothing | **B10.** Remove until shipped |
| `paywall_plus_3` "Notes to flashcards, spaced review" | Recall-to-cards and spaced review are free (`RecapScreen.kt:84`). The paid "bulk notes import" is not built | **B10.** Remove or reword to what Plus actually adds |
| `paywall_plus_2` "Chrome and laptop blocking" | The extension does not check entitlements, so it works the same for free users. Selling it as Plus is misleading (and gating it later would take it away from current users) | **B10.** Either gate `desktop_sync` in the extension and backend, or drop it from the Plus list |
| `paywall_plus_1` "Unlimited schedules and devices" | There is no device limit in the free tier | **B10.** Say "Unlimited schedules" |
| `partner_share_text` "…You'll only see my emergency unlock requests and a weekly summary…" | The key-holder weekly summary does not exist yet (`partner_holder_summary_todo`; backend lacks `/v1/keyholder/summary`) | Drop "and a weekly summary" until 12b and 12a ship it |
| `focus_unlock_note_told` "%1$s gets told." and Setup "They'll get a note when you ask for an emergency unlock" (`SetupScreen.kt:151`, hard-coded) | There is no push notification. The key holder sees the request only when they open Steady › Key holder › Refresh | Reword: "%1$s can approve it in Steady", or add push (and update Data safety for the push provider) |
| `settings_delete_body` "Server-side deletion is coming soon…" | Accurate today, but it means Play's deletion requirement is unmet | **B6.** Replace once `DELETE /v1/me` ships |
| `disclosure_reads_body` "The screen layout…" | Understates what is read (labels and content descriptions) | **B4.** See `play-accessibility-declaration.md` §4 |
| Hard-coded English in `feature/setup/SetupScreen.kt` and `feature/today/TodayScreen.kt` (for example "What pulls you away?", "Try opening Instagram") | Not a claims problem. These cannot be translated for Hindi listings and cannot be reviewed in the strings files | Move to `strings.xml` (12a, or a later phase) |
| `paywall_free_price` "₹0" on all locales; `paywall_default_monthly` "₹149" | Shown to US users if Play prices fail to load | Use Play's formatted price only; hide the price row if unavailable |

Prototype only (`prototype/steady-prototype.html`, **not shipped**, do not reuse in marketing
without edits):
- "ADHD brains want something new right now." The framing is fine, but do not state it as fact in
  ads. Say "When an urge hits, a quick card is easier than just saying no."
- "Pre-committed limits that are costly to override reduced phone use in a 2,000-person trial."
  This is accurate about the study, but next to our screens it implies Steady was tested. In
  marketing, say "Inspired by research on commitment devices".
- "Retrieval practice helps ADHD students as much as peers". The study also found it does not close
  the encoding gap. Do not use this in the store listing.

## 4. Sign-off process

Every external text (listing, ad, creator brief, release notes) gets two checks:
1. Search it for the words in §2's forbidden column, and for "ADHD" next to a verb (treat, reduce, manage, improve, help with).
2. Product and legal owners sign off, with the date recorded in the PR or the ticket.
