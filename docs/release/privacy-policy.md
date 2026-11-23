# Steady privacy policy (draft)

> **LEGAL REVIEW NEEDED before publishing.** This draft was written by the engineering team from
> what the code does at Phase 12c. A lawyer qualified in India (DPDP Act 2023 and DPDP Rules 2025)
> and in the US (FTC Act, COPPA, state privacy and consumer-health laws) must review it. If we offer
> the app in the EU or UK, a GDPR lawyer must review it too. Items in **[brackets]** are placeholders.
> Items marked ⚠ are open legal questions, listed at the end. Publish at a stable HTTPS URL, link it
> from the Play listing, the Chrome Web Store listing and inside the app (B20), and keep a
> dated changelog.

Last updated: [date] · Version [1.0]

---

## The short version

- **Without an account, everything stays on your phone.** Your plan, focus sessions, flashcards,
  tasks and energy check-ins are stored on your device only.
- **Accessibility is used on the phone only.** During your study blocks, Steady looks at
  Instagram, YouTube, Snapchat, Facebook and any apps you chose to block, only to tell which
  section is open. Your screen contents, messages and what you watch are never stored or sent to
  anyone.
- **If you create an account**, we store your phone number or email, your first name, your plan
  rules, and the start and end times and counts of your focus sessions, so your plan syncs to
  Chrome and your key holder can help.
- **Your key holder sees only what you send them**: your name, the reasons you type when you ask
  for an emergency unlock, and (once available) a weekly summary of minutes and blocks. They never
  see your screen, messages or what you watched.
- **No ads, no ad SDKs, no analytics SDKs, no selling or renting of data.** We never use your data
  for advertising.
- **You can delete your synced data** in the app (Settings › Delete my synced data) or at
  [deletion URL].

## Who we are

Steady ("we") is provided by **[legal entity name, registered address, country]**.
For India, we are the Data Fiduciary under the Digital Personal Data Protection Act, 2023.
For the EU or UK (if offered), we are the controller.

Contact for privacy questions and complaints: **[privacy@domain]**.

**Grievance Officer (India)**: **[Name]**, **[designation]**, **[email]**, **[postal address]**.
We acknowledge complaints within [48 hours] and resolve them within the time the DPDP Rules
require (currently up to 90 days). ⚠ Confirm the deadline and whether we must also appoint a
Data Protection Officer (only Significant Data Fiduciaries must).

## What Steady does on your phone (not collected by us)

| What | Why | Where it stays |
|------|-----|----------------|
| Screen layout and on-screen labels of Instagram, YouTube, Snapchat, Facebook and apps you chose to block, read through Android's Accessibility service, **only during your blocks** | To notice when Reels, Shorts, Spotlight or Facebook Reels open, press Back (or Home for a fully blocked app) and show a short pause screen | In memory for a moment, then dropped. Never stored, never sent |
| Each time a short-video section is caught: which section and when | To show "urges caught" today and per week | On your phone |
| Flashcards, study tasks and first steps, energy check-ins | Study features | On your phone |
| Your plan (schedules, distractions, key holder name, unlock settings) | To run blocks on time | On your phone (and synced if you sign in) |

Android backups are turned off for this data, so it does not go to your Google backup.

## What we collect if you create an account

The account is optional. You need it only to sync to Chrome, to use a key holder, and to get
support for a subscription across devices.

| Data | Why we use it | Legal basis (GDPR, if applicable) |
|------|---------------|-----------------------------------|
| Phone number or email | Sign-in with a one-time code; account recovery | Contract |
| First name | Shown to your key holder | Contract |
| Plan rules: distractions you chose (sections, apps, websites), schedule names and times, key holder name and relation (self, friend or parent), unlock settings, blocked websites | Sync between your phone and Chrome | Contract |
| Focus sessions: start and end time, number of intercepts, number of unlocks | Weekly summary for you and your key holder | Contract |
| Emergency unlock requests: which section, the reason you type, the time, and whether it was approved | Sent to your key holder so they can approve or decline | Contract |
| Key holder links and invite codes | Connect you and your key holder | Contract |
| Subscription: product and Google Play purchase token (once server checks are live) | Confirm Steady Plus | Contract; legitimate interest (fraud prevention) |
| Technical logs: IP address, time, request path, error codes | Security, abuse prevention, debugging. Kept for [14 days] | Legitimate interest |

We do **not** collect: screen contents, messages, browsing or watch history, which videos you
opened, location, contacts, photos, advertising IDs, or minute-by-minute app usage.

If you sign in without typing a name, we show your key holder the part of your email before the "@",
or the last 4 digits of your phone number. ⚠ Engineering is asked to require a name instead (see
B16 in the launch checklist).

## The Chrome extension

The extension stores your settings, sign-in token, your last plan and its own unlock timers in
your browser. It sends your email or phone and the code to sign in, then only downloads your plan
and the blocking rules. It does not send page addresses, page contents, browsing history or unlock
reasons anywhere.

## Who we share data with

- **Your key holder**: only what is listed above, and only after you invite them and they accept.
  You or they can remove the link at any time (once the unlink feature ships).
- **Service providers** that process data for us under contract: hosting and database
  **[provider, region]**, the SMS or email service that delivers sign-in codes **[provider]**, and
  Google Play for payments. Google handles your payment details; we never see your card or UPI
  details.
- **Authorities**, when the law requires it.

We do not sell personal data, and we do not share it for advertising. Steady contains no
advertising or analytics SDKs.

## How long we keep data

| Data | Kept until |
|------|-----------|
| Account, plan, sessions, links | You delete your synced data, or [24 months] with no sign-in ⚠ (policy decision) |
| Sign-in tokens | 90 days of no use (sliding), or sign-out |
| One-time codes | 10 minutes (used or expired codes deleted within [24 hours]) |
| Unlock requests | [12 months] |
| Technical logs | [14 days] |
| Backups | Deleted data leaves backups within [30 days] |

When you delete your synced data, we erase your account, plan, sessions, unlock requests,
invites, key holder links (both ways), subscription status and tokens. If you held someone
else's key, their records keep your decision ("approved" or "declined") but no longer link it to you.

## Your rights and choices

- **Withdraw consent and turn things off**: turn off Accessibility in Android Settings at any
  time. Sign out to stop syncing. Uninstall to remove all local data.
- **Access, correction, erasure**: in the app (edit your plan and name; Settings › Delete my synced
  data), at [deletion URL], or by writing to [privacy@domain].
- **India (DPDP)**: right to information about processing, correction and erasure, grievance
  redressal, and nominating someone to act for you if you die or become incapacitated. You may
  complain to the Data Protection Board of India after using our grievance process.
- **EU/UK (if offered)**: access, rectification, erasure, restriction, portability, objection, and
  the right to complain to your supervisory authority. ⚠ An Art. 27 representative is needed if we
  target the EU or UK.
- **US**: depending on your state, you may have rights to know, delete and correct. We do not sell
  or "share" (for cross-context advertising) personal information.

## Children and teens

- Steady is **not directed at children under 13** and is not designed for them. We do not
  knowingly collect personal information from children under 13 (US COPPA). If we learn that we
  have, we delete it. Parents can contact [privacy@domain].
- **India, under 18**: the DPDP Act treats everyone under 18 as a child. The DPDP Rules require
  **verifiable parental consent** before we process a child's personal data. These obligations
  apply from about **May 2027** (18 months after the Rules were notified in November 2025). ⚠ Before
  then we will add an age check at account creation and a parent consent flow for under-18 users.
  Without an account, Steady processes nothing about you on our servers.
- We never use children's data for tracking, behavioural monitoring or targeted advertising.
  ⚠ Lawyers must decide whether syncing a teen's focus-session counts to a parent key holder is
  "behavioural monitoring" under DPDP s.9(3), and whether an exemption applies.

## Security

Data in transit uses HTTPS (TLS). Sign-in codes and tokens are stored on our servers only as
one-way hashes. Every request is checked against a strict format, so screen contents cannot be
stored even by mistake. On your phone, the sign-in token is kept in app-private storage
⚠ (to be wrapped with Android Keystore before release, B8).

**Breaches**: if a personal data breach affects you, we will tell you without undue delay. We will
tell the Data Protection Board of India (and other regulators where required) within the time the
law requires: India's Rules ask for a detailed report within 72 hours. Steady is marketed for
focus and study habits, not as a health product. Even so, we follow the spirit of the US FTC
Health Breach Notification Rule. We never send data to advertising platforms, and an unauthorised
disclosure would be handled as a breach and notified. ⚠ Lawyers to confirm whether the HBNR or
state consumer-health laws (for example Washington's My Health My Data Act) apply because of
ADHD-related marketing.

## International transfers

Our servers are in **[region]**. If you use Steady from another country, your data is processed
there. ⚠ Check DPDP cross-border rules (the government may restrict certain countries) and GDPR
transfer mechanisms if offered in the EU or UK.

## Changes

We will post changes here and, for important changes, tell you in the app before they take effect.

---

## Open legal questions (remove before publishing)

1. DPDP s.9(3): is a parent key holder seeing a teen's weekly counts "behavioural monitoring"?
   Does the education-institution exemption help institute deployments?
2. Verifiable parental consent mechanism for under-18s (DigiLocker, or relying on an adult parent's
   own account?) and the age-gate UX. It must be ready before May 2027.
3. Do the FTC HBNR, the Washington MHMDA, or other consumer-health laws apply given "designed with ADHD in mind" marketing?
4. Retention periods (marked [ ] above): pick them and implement them as backend jobs.
5. Whether we are likely to be a Significant Data Fiduciary (no, at launch scale) and the DPO requirement.
6. EU/UK launch: Art. 27 representative, DPIA for accessibility-based processing.
7. Terms of Service and subscription terms (auto-renewal disclosures under US state laws and ROSCA).
