# Play Console: Data safety form

These answers are derived from the code at Phase 12c. Change them in the same release as any
change to `backend/src/schemas.ts`, `backend/src/db.ts`, `android/app/src/main/java/app/steady/remote/`
or the extension's `src/api.ts`. Blocking issues use the ids from
[`launch-checklist.md`](launch-checklist.md#blocking-issues-policy-vs-code).

Play definitions used below:
- **Collected**: data sent off the device to us (the Steady backend) or to a service provider acting for us.
- **Shared**: data transferred to a third party. Transfers to service providers, and transfers the user starts and would expect (such as sending an unlock request to their own key holder), are exempt.
- Data processed only on the device and never sent is **not collected**. This covers the
  accessibility tree, local intercept events, flashcards, tasks and energy check-ins.

## 1. Inventory: what exists and where

| Data | Where it lives | Leaves device? | Source |
|------|----------------|----------------|--------|
| Accessibility node tree (view ids, labels) | Memory only, dropped after each check | Never | `enforcement/NodeTree.kt` |
| Intercept events (surface id + time) | Room `intercept_events` | Never as events. Only as a per-session count | `data/db/Entities.kt` |
| Flashcards, tasks + first steps, energy check-ins | Room `cards`, `tasks`, `energy_checkins` | Never | `data/db/Entities.kt` |
| Local unlock grants, unlock request copies | Room `unlock_grants`, `unlock_requests` | Request reason, yes (see below) | `data/db/Entities.kt` |
| **Contact**: phone number or email (optional account) | Server `users.contact`, `otp_codes.contact`; device `steady_account` prefs | Yes, if the user signs in | `backend/src/db.ts`, `remote/AccountStore.kt` |
| **Display name**: first name typed at sign-in; if none is given, the server uses the email local part or the last 4 phone digits | Server `users.name` | Yes. Shown to the user's key holder | `backend/src/app.ts` `defaultName()` |
| **Plan rules**: chosen distractions (`instagram.reels`, `app:<pkg>`, `site:<domain>`), schedules with free-text labels, key holder name + relation (self/friend/parent), unlock policy, blocked domains | Room `plan`; server `plans.body` | Yes, if signed in (sync to phone + Chrome) | `rules/Plan`, `backend/src/schemas.ts` `PlanSchema` |
| **Focus sessions**: id, schedule id, start, end, intercept count, unlock count | Room `sessions`; server `sessions` | Yes, if signed in | `remote/SessionUploader.kt`, `FocusSessionSchema` |
| **Unlock request**: surface id, typed reason (≤ 280 chars), status, decision time | Server `unlock_requests` | Yes. The reason is shown to the linked key holder | `UnlockRequestSchema`, `GET /v1/keyholder/requests` |
| **Key-holder links and invites** | Server `keyholder_links`, `invites` | Yes | `backend/src/db.ts` |
| **Purchase**: productId + Play purchase token | Today: device only (Play Billing). Planned: `POST /v1/billing/verify` | **Not yet (B9)**. Becomes yes when verification ships | `remote/PlayBillingGateway.kt`, `docs/CONTRACTS.md` |
| Entitlement tier (free/plus) | Server `entitlements` | Server-side only | `backend/src/entitlements.ts` |
| Auth token | Device prefs (plain, B8), server stores a SHA-256 hash | Credential, not a data type | `AccountStore.kt`, `backend/src/app.ts` |
| Time zone (IANA id) on `GET /v1/summary/weekly?tz=` | Not stored | Sent per request | `ApiClient.weeklySummary` |
| IP address, user agent | Hosting and reverse-proxy logs | Yes, infrastructure | Deployment, not code. Keep log retention short and do not use IP for location |
| Extension: settings (API base, token), cached plan, detectors, local grants | `chrome.storage.local` | Only the OTP contact and code at sign-in, plus GETs of the plan and detectors | `extension/src/storage.ts`, `extension/README.md` |

Not present anywhere (verified): advertising ID, Android ID, analytics SDKs, ad SDKs, crash
reporting SDKs, location, contacts, photos, files, health sensors, push tokens.
`android/app/build.gradle.kts` depends only on AndroidX, Compose, Room, OkHttp and Play Billing.
Downloadable Google Fonts are fetched through Google Play services and carry no user data.

## 2. Form answers

### Data collection and security

| Question | Answer | Why |
|----------|--------|-----|
| Does your app collect or share any of the required user data types? | **Yes** | Optional account: contact, name, plan, sessions, unlock reasons |
| Is all of the user data collected by your app encrypted in transit? | **Yes, only after B5 is fixed** | Release `network_security_config.xml` is HTTPS-only, but it is **not referenced** from the manifest, and the default API base is `http://10.0.2.2:8787` (`AccountStore.kt:37`). Ship an HTTPS production URL and wire the config first. Until then, answer No or do not publish. |
| Do you provide a way for users to request that their data is deleted? | **Yes, only after B6 is fixed** | Contract has `DELETE /v1/me`; backend does not implement it yet, and the Settings string says "Server-side deletion is coming soon". Play also needs a **web** deletion link for apps that let users create an account. |

### Data types

Answer "Collected = Yes, Shared = No" for every row. "Optional" means the user can use the app
without providing the data: the account is optional (`settings_account_why`).

| Play category › type | Collected | Shared | Processed ephemerally? | Required / optional | Purposes |
|----------------------|-----------|--------|------------------------|---------------------|----------|
| Personal info › Email address | Yes | No | No | Optional | App functionality, Account management |
| Personal info › Phone number | Yes | No | No | Optional | App functionality, Account management |
| Personal info › Name | Yes | No (shown to key holder at the user's request, an exempt user-initiated transfer) | No | Optional | App functionality, Account management |
| App activity › App interactions (focus session start/end, intercept and unlock counts) | Yes | No | No | Optional | App functionality (weekly summary, key holder summary) |
| App activity › Other user-generated content (plan: schedule labels, blocked sites and apps, key holder name and relation) | Yes | No | No | Optional | App functionality (sync phone ⇄ Chrome) |
| Messages › Other in-app messages (unlock request reason sent to the key holder) | Yes | No (exempt user-initiated transfer to the user's chosen key holder) | No | Optional | App functionality |
| Financial info › Purchase history | **No today. Yes once B9 ships** (productId + purchase token sent for verification) | No | No | Optional | App functionality (entitlement), Fraud prevention |

Answer **No** for: Location, Web browsing (the extension reads URL paths locally only), Installed apps,
Contacts, Photos/videos, Audio, Files, Calendar, Health and fitness, Device or other IDs, App info
and performance (no crash logs or diagnostics are collected).

Notes for reviewers and lawyers:
- **Installed apps.** The plan can contain `app:<package>` for apps the user picked from a fixed list
  (`feature/setup/SetupModels.kt`, for example BGMI). The app does not read the installed-apps list.
  We treat these as user-generated content. If the picker ever lists apps found on the device,
  switch to "Installed apps: Yes".
- **Health and fitness.** Nothing in the data is health data in Play's sense (no symptoms, no
  medical info). Do not add an ADHD questionnaire or screener without redoing this form and the
  claims review (`wellness-claims-guide.md`).
- **Key holder sharing.** The key holder (another Steady user) sees: the owner's display name,
  unlock reasons and surfaces, request times and, once built, a weekly summary. This is a transfer
  the user starts and expects, so it is exempt from "shared", but the privacy policy must say it.
- **Service providers** (not "sharing"): hosting, database, the OTP SMS/email provider (B7), and
  Google Play for billing. List them in the privacy policy.

### Security practices section

| Question | Answer |
|----------|--------|
| Data is encrypted in transit | Yes (after B5) |
| You can request that data be deleted | Yes (after B6). In-app: Settings › Delete my synced data → `DELETE /v1/me`. Web: deletion page URL (to create) |
| Committed to follow the Play Families Policy | No (the app is not designed for children; target audience 13+) |
| Independent security review | No |

## 3. Deletion: what DELETE /v1/me must erase

Per `docs/CONTRACTS.md` (Phase 12 additions): plan, sessions, unlock requests (as owner), invites,
key-holder links both ways, entitlements and tokens. To make the form answer true:
- Also delete the `users` row (contact, name) and `otp_codes` rows for that contact. The contract
  list does not name them. **Confirm 12b deletes them**, or the "account deletion" answer is false.
- `unlock_requests.decided_by` is `ON DELETE SET NULL`: decisions the user made as a key holder stay
  on the owner's record without their identity. That is fine, but say so in the privacy policy.
- Backups: say how long database backups keep deleted data (proposed: 30 days).
- Android: after the 204, sign out and clear `steady_account` prefs. Local Room data stays until the
  user clears app data or uninstalls. Say so in the confirmation text.
- Web deletion page: a form that sends an OTP to the contact and then calls the same deletion.
  Put its URL in Play Console › Data safety › Delete account URL.

## 4. Chrome Web Store "Privacy practices" tab

| Data type | Collected? | Notes |
|-----------|------------|-------|
| Personally identifiable information | Yes | Email or phone typed on the Options page for OTP sign-in |
| Authentication information | Yes | Bearer token stored in `chrome.storage.local` |
| Health, financial, location, personal communications | No | |
| Web history | No | URL paths are checked locally against the plan; never sent |
| User activity | No | No clicks or keystrokes recorded. Unlock reasons are not stored (extension README) |
| Website content | No | Content scripts look at tags and attributes only |

Certify: not sold to third parties; not used or transferred for purposes unrelated to the single
purpose; not used for creditworthiness or lending.
