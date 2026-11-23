# Launch checklist: first internal-testing release

Goal: an Android App Bundle on the Play **internal testing** track, talking to a deployed HTTPS
backend, plus a private Chrome Web Store item for the same testers. Steps are in order. Do not start a
step until the steps before it are done.

Related docs:
[`play-accessibility-declaration.md`](play-accessibility-declaration.md) ·
[`play-data-safety.md`](play-data-safety.md) ·
[`privacy-policy.md`](privacy-policy.md) ·
[`wellness-claims-guide.md`](wellness-claims-guide.md) ·
[`store-listing.md`](store-listing.md) ·
[`qa-test-plan.md`](qa-test-plan.md)

None of this is legal advice. The policy summaries reflect Google Play and Chrome Web Store
policy as we understand it in November 2026. Check each one against the live policy page before you
submit.

---

## Blocking issues (policy vs code)

Found in the Phase 12c review of the code at commit `97fbb28`. Phases 12a (Android integration) and
12b (backend and extension hardening) are running in parallel, so re-check every row against
`main` after they merge.

**Gate** says the latest point by which the issue must be fixed:
**Internal** = before the first internal-testing upload · **Testing** = before closed or open
testing with outside testers · **Prod** = before production · **Plus** = before selling Plus.

| Id | Gate | Issue | Code | Policy / doc | Owner |
|----|------|-------|------|--------------|-------|
| B1 | Internal | `PACKAGE_USAGE_STATS` declared but never used. This contradicts the usage-free design and the privacy copy. Unused sensitive permissions draw review questions | `AndroidManifest.xml` (no usage in code) | Play: request only the permissions you need; Data safety | 12a |
| B2 | Internal | `flagRetrieveInteractiveWindows` declared but unused (only `rootInActiveWindow` is read) | `res/xml/accessibility_service_config.xml` | Play Accessibility API: minimum necessary | 12a |
| B3 | Internal | Prominent disclosure only reachable from Settings › Accessibility disclosure. Setup and Today never show it, so users must dig into a menu to find it | `feature/settings/SettingsScreen.kt:109`, no route from Setup/Today (`docs/INTEGRATION_TODO.md`) | Play User Data policy: the disclosure must appear in the normal flow, not only in settings | 12a |
| B4 | Internal | Disclosure says "screen layout" but on-screen text and content descriptions are read in memory and matched; it doesn't say that Home is pressed for whole-app blocks | `strings_enforcement.xml` (`disclosure_reads_body`, `enforcement_a11y_description`), `enforcement/NodeTree.kt`, `shared/detectors/detectors.v1.json` | Disclosure must describe the data accessed and how it is used. Wording fix in `play-accessibility-declaration.md` §4 | 12a |
| B5 | Internal | Release default API base is `http://10.0.2.2:8787`; Settings lets users type any server; `network_security_config.xml` is not referenced from the manifest | `remote/AccountStore.kt:37`, `SettingsScreen.kt`, `AndroidManifest.xml` | Data safety "encrypted in transit" must be true | 12a |
| B6 | Testing | No server-side deletion: `DELETE /v1/me` not implemented; Settings says "coming soon"; no web deletion URL | `backend/src/app.ts` (no route), `strings_account.xml` `settings_delete_body`, `RemoteModule.signOut` | Play account deletion requirement (in-app plus web link); DPDP and GDPR erasure. Deletion must also remove the `users` and `otp_codes` rows | 12b + 12a |
| B7 | Internal | OTP codes and full contacts are written to stdout (`consoleOtpSender`), and there is no real SMS or email delivery | `backend/src/app.ts:39-41`, `:143` | Security; privacy policy (processor list) | 12b |
| B8 | Testing | Bearer tokens never expire; the token is stored unencrypted in SharedPreferences | backend README "Tokens do not expire yet"; `remote/AccountStore.kt` TODO | Contract (90-day sliding expiry); reasonable security (DPDP s.8(5)) | 12b + 12a |
| B9 | Plus | No server-side purchase verification. Purchase tokens are not sent; entitlement trusts the device; `POST /v1/billing/verify` is not wired | `remote/PlayBillingGateway.kt` comment, `RemoteEntitlementRepository` | Contract; Data safety (Purchase history becomes "Yes" once wired) | 12b + 12a |
| B10 | Plus | The paywall sells features that don't exist or aren't gated: "Task breakdown help", "Notes to flashcards" (bulk import not built; recall-to-cards is free), "Chrome and laptop blocking" (the extension doesn't check entitlements), "Unlimited … devices" (no device limit) | `strings_account.xml` `paywall_plus_1..4`, `feature/paywall/FreeTier.kt`, `RecapScreen.kt:84`, extension | Play Payments and Subscriptions policy (accurate offer); Misleading Claims; consumer law | product + 12a |
| B11 | Internal (verify) | `targetSdk = 35` and Play Billing Library 7.1.1. Play's yearly target-API rule (Android 16 / API 36 for new apps and updates from 31 Aug 2026) and the PBL deprecation schedule (PBL 7 → 8+ by 31 Aug 2026) probably reject this upload. Check the current dates in Play Console › Policy status | `android/app/build.gradle.kts:14`, `gradle/libs.versions.toml` (`billing = "7.1.1"`) | Play target API level and PBL version requirements | 12a |
| B12 | Internal | Manifest lacks `RECEIVE_BOOT_COMPLETED`, `BlockAlarmReceiver` and `BootReceiver`, so blocks don't start on time or survive a reboot. `MainActivity` doesn't read `EXTRA_ROUTE`, so the overlay's Swap and the notification tap go nowhere. `POST_NOTIFICATIONS` is never requested | `enforcement/README.md` "Manifest additions", `enforcement/Launch.kt` TODO | Needed for the FGS and Accessibility demo videos; FGS policy (notification must be visible) | 12a |
| B13 | Testing | Strict mode can be undone from inside Steady: Settings › Edit my plan works during a strict block (QA S-08). Store copy "Strict blocks end on time" is false until this is fixed | `feature/setup/*`, `feature/settings/SettingsScreen.kt` (no block check) | Misleading Claims; `wellness-claims-guide.md` rule 6 | product + 12a |
| B14 | Testing (CWS) | Extension manifest ships `http://localhost/*` and `http://127.0.0.1/*` hosts and the placeholder `https://api.steady.app/*`; Options lets users type any API base | `extension/static/manifest.json`, `extension/src/ui/options.ts` | CWS: minimum permissions; data in transit | 12b |
| B15 | Testing | A plan can block any `app:<pkg>`, including Settings, the package installer or the launcher. That would stop the user turning the service off or uninstalling. No denylist in the app or the backend schema | `enforcement/EnforcementDecider.kt` `observedPackages`, `backend/src/schemas.ts` `SURFACE_ID` | Play Accessibility API: must not prevent the user from disabling or uninstalling an app or service | 12a + 12b |
| B16 | Testing | If no name is typed, the key holder sees the owner's email local part or the last 4 phone digits | `backend/src/app.ts:74` `defaultName()` | Data minimisation; privacy policy must disclose it. Prefer requiring a name | 12b |
| B17 | Prod (India by ~May 2027) | No age check and no parental consent, though the audience includes JEE/NEET and board students (under 18). Teen session counts can be synced to a parent key holder | Setup / Settings (none) | DPDP Act s.9 and DPDP Rules 2025 (verifiable parental consent; no behavioural monitoring of children); Play target-audience declaration | product + legal |
| B18 | Internal | Application id `app.steady` and the name "Steady" are working names. The package name can never change after the first upload | `android/app/build.gradle.kts`, `docs/CONTRACTS.md` | Play: package names are permanent | product |
| B19 | Testing | Copy promises things that don't happen: key holder "gets told" or "will get a note" (no push, they must refresh); the invite says the key holder sees "a weekly summary" (not built) | `strings_focus.xml` `focus_unlock_note_told`, `SetupScreen.kt:151`, `strings_account.xml` `partner_share_text` | Misleading Claims; accuracy of privacy disclosures | 12a |
| B20 | Internal | No privacy policy link in the app (and none hosted yet) | `feature/settings/SettingsScreen.kt` | Play User Data policy (privacy policy in the listing **and** in the app); CWS | product + 12a |

Not blocking, but track these:
- **R1**: the remote detector config is not applied (bundled asset only), so a broken detector
  needs an app release (`docs/INTEGRATION_TODO.md`; QA §3 step 9).
- **R2**: `FocusService` notes that enforcement doesn't depend on it. Be ready to justify
  `specialUse` to the FGS reviewer.
- **R3**: no telemetry by design, so detector breakage is found only by QA and user reports. Keep a
  manual watch on target-app updates.
- **R4**: `proguard-rules.pro` is empty. Verify the minified release build (type-safe Navigation
  routes, kotlinx-serialization, Room) with QA ★ cases.
- **R5**: `paywall_default_*` falls back to ₹ prices for every locale.
- **R6**: Android 17 Advanced Protection Mode revokes accessibility for non-tool apps. There is no
  fallback (BLOCK_DOMAIN is not built).
- **R7**: hard-coded English strings in Setup and Today block a Hindi listing.


---

## Steps to the first internal-testing release

### 0. Decisions (product)
1. Final app name and **application id** (B18). Run a trademark search in India (IP India) and the
   US (USPTO) for the name. Update `applicationId`, `namespace` if wanted, `docs/CONTRACTS.md`,
   `manageSubscriptionUrl` default package, and the extension name.
2. Legal entity, support email and privacy email. Appoint the grievance officer (privacy policy placeholders).
3. Production API domain (replaces `api.steady.app`) and hosting region. Prefer India (for example
   Mumbai) for the India launch.
4. Target audience for Play: **13+**, not designed for children (no Families program).

### 1. Code ready
5. Merge 12a and 12b. Fix every **Internal** row in the blocking table. Re-run
   `cd android && ./gradlew build`, `cd backend && npm test`, `cd extension && npm test && npm run build`.
6. Wellness review of all strings ([`wellness-claims-guide.md`](wellness-claims-guide.md) §3 and §4).

### 2. Versioning
7. Android: `versionCode` is a single increasing integer. Every upload needs a new one, and Play
   never accepts a lower one. Use `versionCode = 1`, `versionName = "0.1.0"` for the first internal
   build, then bump `versionCode` on every upload. Keep `versionName` on semver `0.x.y` until
   production.
8. Extension: bump `version` in `extension/static/manifest.json` (now `0.10.0`) with each CWS upload.
   It may differ from Android's.
9. Backend: tag the deployed commit. Optionally expose the commit through `GET /v1/health`.
10. Git tags: `android-v0.1.0+1`, `backend-<date>`, `extension-v0.10.0`. Release notes in the PR.

### 3. Signing and Play App Signing
11. Generate the **upload key** (keep it out of the repo):
    ```sh
    keytool -genkeypair -v -keystore steady-upload.jks -alias upload \
      -keyalg RSA -keysize 4096 -validity 10000
    ```
    Store the keystore and passwords in the team password manager, with an offline encrypted backup.
12. Add a `release` `signingConfig` to `android/app/build.gradle.kts` that reads the path, passwords
    and alias from environment variables or `~/.gradle/gradle.properties`. Never commit them.
    (This is a code change: hand it to 12a or a follow-up PR.)
13. Create the app in Play Console and enrol in **Play App Signing** (Google holds the app signing
    key; you upload with the upload key). Record both SHA-256 fingerprints in the team vault.
14. Build: `cd android && ./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
    Check with Android Studio › Analyze APK: no native libraries (16 KB page-size rule is not an
    issue), only the expected permissions (no `PACKAGE_USAGE_STATS` after B1; `BILLING` comes from
    the billing library).

### 4. Backend deploy (staging, then production)
15. Host: one Node 22 instance with a **persistent disk**. `node:sqlite` is single-writer, so run
    one replica only. TLS goes through a reverse proxy (for example Caddy or nginx) with HSTS.
    Redirect HTTP to HTTPS.
16. Environment variables (from `backend/README.md`; re-check after 12b adds more):

    | Name | Value |
    |------|-------|
    | `PORT` | Internal port behind the proxy, for example `8787` |
    | `DATABASE_PATH` | Path on the persistent disk, for example `/data/steady.db` |
    | `DETECTORS_PATH` | Path to the deployed `shared/detectors/detectors.v1.json` |
    | `CORS_ORIGINS` | Exact web origins if any (`chrome-extension://*` is always allowed; ask 12b to restrict it to the CWS item id) |
    | `NODE_ENV` | `production`. **Never `test`**: that makes every OTP `000000` |
    | OTP provider credentials (B7) | Names as defined by 12b. Store them as secrets, not in the repo |
    | Play billing verifier credentials (B9) | Service-account JSON path or contents, as defined by 12b. Without them production returns 503 `billing_not_configured` |

17. Backups: nightly `sqlite3 /data/steady.db ".backup ..."`, or continuous replication (Litestream),
    encrypted, kept for 30 days to match the privacy policy. Test a restore once.
18. Logs: no request bodies, no OTP codes, no contacts (B7). Keep them for 14 days.
19. Smoke tests: QA §9 (B-01 to B-06) against staging, then production.

### 5. Monitoring
20. Uptime check on `GET /v1/health` every minute, with an alert to the on-call chat or email.
21. Alerts on 5xx rate, disk usage and backup failures.
22. Android: **Play Console › Android vitals** for crashes and ANRs. No crash SDK, by design.
    Review the pre-launch report for each upload.
23. Detector watch: a weekly manual run of QA E-01 to E-05 on D1 and D3, and on every Instagram or
    YouTube update during the beta.

### 6. Hosted pages
24. Publish the privacy policy ([`privacy-policy.md`](privacy-policy.md), after legal review) at a
    stable HTTPS URL. Add the in-app link (B20).
25. Publish the web account-deletion page (B6). Its URL goes into Data safety.

### 7. Play Console: app content
26. Privacy policy URL.
27. Ads: **No ads.**
28. App access: explain that the account is optional. Give a **reviewer test account** for the key
    holder and sync features. Reviewers cannot receive an OTP, so 12b must provide a reviewer
    contact with a fixed code, allow-listed by an environment variable and production-safe.
    Include instructions for making a block active now.
29. Content rating (IARC questionnaire): no violence, no user-to-user chat beyond unlock reasons to
    a chosen key holder. Answer the "users can interact" questions truthfully.
30. Target audience: 13–15, 16–17, 18+ (not under 13). Answer the "appeals to children" question: No.
31. Data safety: from [`play-data-safety.md`](play-data-safety.md).
32. Health apps declaration: not a health or medical app. Category: Productivity.
33. **Accessibility API declaration** and video: [`play-accessibility-declaration.md`](play-accessibility-declaration.md).
34. **Foreground service declaration**: `specialUse`, describing a user-visible countdown for a
    scheduled study block. Use the same video (section 6 of the declaration doc).
35. Financial features: none. Government app: no. News app: no.

### 8. Listing and products
36. Store listing (en-IN and en-US) and graphics from [`store-listing.md`](store-listing.md).
37. Subscriptions `steady_plus_monthly` and `steady_plus_yearly` with base plans, INR and USD prices.
    Keep them **inactive** or unlisted until the Plus gate (B9, B10) is cleared. Internal testers
    can still use license testing (QA §6).
38. License testers: Play Console › Settings › License testing.

### 9. Internal testing release
39. Testing › Internal testing → create a tester list (up to 100 emails) → upload the AAB → release
    notes → roll out. Share the opt-in link with testers.
40. Run the QA ★ cases ([`qa-test-plan.md`](qa-test-plan.md)) on the build from the Play track, not on a
    sideloaded APK.

### 10. Chrome Web Store (private)
41. Developer account with 2-step verification. Upload the `dist/` ZIP. Visibility: **Private**
    (trusted testers) or Unlisted.
42. Fill in the single purpose statement, permission justifications and privacy practices from
    [`store-listing.md`](store-listing.md) §5 and [`play-data-safety.md`](play-data-safety.md) §4.
    The B14 fixes must be in the ZIP.
43. Run QA §8.

### 11. Go / no-go for widening the test
44. All **Internal** and **Testing** rows closed; QA ★ all pass on D1 to D5; no open P1 crash in
    vitals; privacy policy legally reviewed; deletion verified end to end (QA B-03).
