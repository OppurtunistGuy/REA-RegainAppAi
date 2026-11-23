# Steady backend (Phase 9, hardened in Phase 12)

Small JSON API that syncs the rules Plan between phone and browser, pairs a key holder
(friend or parent) who approves emergency unlocks, stores aggregate focus sessions for the
weekly summary, serves the detector config, verifies Play purchases and reports entitlements.

Stack: Node 22, TypeScript (strict), Hono + `@hono/node-server`, built-in `node:sqlite`
(no native deps), zod, vitest. The API is described in [`../shared/api/openapi.yaml`](../shared/api/openapi.yaml)
and fixed by `docs/CONTRACTS.md` (Phase 9 + "Phase 12 additions").

## Run

```sh
npm ci
npm run dev        # tsx watch, http://localhost:8787
npm run build      # tsc → dist/
npm start          # node dist/server.js (reads the env vars below)
npm test           # vitest run (in-memory SQLite)
npm run typecheck  # tsc --noEmit (src + tests)
```

Production-style start:

```sh
npm ci && npm run build
NODE_ENV=production PORT=8787 DATABASE_PATH=/var/lib/steady/steady.db TRUST_PROXY=1 \
  OTP_EMAIL_WEBHOOK_URL=https://… OTP_SMS_WEBHOOK_URL=https://… OTP_PROVIDER_TOKEN=… \
  GOOGLE_PLAY_PACKAGE_NAME=app.steady GOOGLE_PLAY_SERVICE_ACCOUNT_FILE=/run/secrets/play.json \
  npm start
```

The start line logs which OTP sender and purchase verifier are active (`console`/`http`/`not
configured`, `dev`/`google_play`/`not configured`).

Quick check (development):

```sh
curl -X POST localhost:8787/v1/auth/otp/start -H 'content-type: application/json' -d '{"contact":"me@example.com"}'
# development only: the code is printed to the server console (contact masked)
curl -X POST localhost:8787/v1/auth/otp/verify -H 'content-type: application/json' \
  -d '{"contact":"me@example.com","code":"123456"}'
```

`node:sqlite` needs no flag on Node ≥ 22.13 but prints an `ExperimentalWarning`; the scripts
and the vitest config pass `--disable-warning=ExperimentalWarning`. On Node 22.5–22.12 add
`--experimental-sqlite` to `NODE_OPTIONS`.

### Docker

`Dockerfile` (node:22-slim, multi-stage, runs as the non-root `node` user, data in the `/data`
volume, healthcheck on `/v1/health`). The detector config lives outside `backend/`, so pass the
repo's `shared/` folder as a named build context (BuildKit, Docker 23+):

```sh
cd backend
docker build --build-context shared=../shared -t steady-backend .
docker run -p 8787:8787 -v steady-data:/data \
  -e OTP_EMAIL_WEBHOOK_URL=… -e OTP_PROVIDER_TOKEN=… -e TRUST_PROXY=1 steady-backend
```

The image sets `NODE_ENV=production`, so without an OTP provider sign-in answers 503
`otp_not_configured`, and without Play credentials billing answers 503 `billing_not_configured`.

## Environment variables

| Name | Default | Meaning |
|------|---------|---------|
| `PORT` | `8787` | HTTP port |
| `HOST` | `0.0.0.0` | Bind address |
| `DATABASE_PATH` | `steady.db` | SQLite file (`:memory:` for throwaway) |
| `DETECTORS_PATH` | `../shared/detectors/detectors.v1.json` | Detector config served by `GET /v1/detectors` |
| `CORS_ORIGINS` | – | Extra comma-separated exact origins. `chrome-extension://*` is always allowed. |
| `TRUST_PROXY` | – | `1`/`true`: use the first `X-Forwarded-For` hop as client IP (rate limits). Set only behind a proxy you control. |
| `NODE_ENV` | – | `test`: every OTP code is `000000`. `production`: no console OTP sender, no dev purchase verifier. |
| `OTP_EMAIL_WEBHOOK_URL` | – | Email code endpoint for `HttpOtpSender` (POST `{to, code, channel, ttlMinutes}`) |
| `OTP_SMS_WEBHOOK_URL` | – | SMS code endpoint for `HttpOtpSender` |
| `OTP_PROVIDER_TOKEN` | – | Bearer token sent to the OTP endpoints |
| `GOOGLE_PLAY_PACKAGE_NAME` | – | e.g. `app.steady`; with a service account enables `GooglePlayVerifier` |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | – | Service-account key JSON (inline) |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_FILE` | – | …or a path to it |

## Layout

```
src/app.ts           createApp({db, clock, otpSender, purchaseVerifier, log, trustProxy, …})
src/server.ts        entry point (env → options)
src/db.ts            openDb() + versioned migrations (PRAGMA user_version)
src/schemas.ts       zod schemas; every body is .strict(); PROTECTED_PACKAGES
src/otp.ts           OtpSender interface, ConsoleOtpSender (dev), HttpOtpSender (skeleton), maskContact
src/billing.ts       PurchaseVerifier interface, DevPurchaseVerifier, GooglePlayVerifier (skeleton)
src/summary.ts       weekly summary math (pure)
src/entitlements.ts  tiers, features, setTier()
src/errors.ts        ApiError → {error: {code, message}}
test/                vitest suites, one per area, plus an OpenAPI ↔ routes check
Dockerfile           production image
```

## Behaviour notes

- **Privacy.** Unknown body fields → 400 `invalid_body`. Error messages name the offending
  field but never echo its value. Sessions store only times and counts. Bodies over 64 KB → 413.
  Users without a `name` are shown to key holders as "Your friend"; nothing is derived from the
  email or phone.
- **Logging.** One line per request: method, *route pattern* (e.g. `/v1/keyholder/invites/:code/accept`,
  never the raw path), status, duration and request id. Bodies, tokens and codes are never logged.
  Contacts are masked (`r***@g***.com`, `••••1234`). Every response has `X-Request-Id` (a sane
  incoming one is echoed). Silent under `NODE_ENV=test`.
- **Headers.** `hono/secure-headers` (nosniff, frame denial, HSTS, `Referrer-Policy: no-referrer`,
  CSP `default-src 'none'`), plus `Cache-Control: no-store`.
- **Auth.** OTP codes (6 digits, 10 min, single use, locked after 5 wrong tries) and bearer
  tokens are stored as SHA-256 hashes. OTP start: 5 per contact per hour (429). OTP verify:
  30 attempts per client IP per 15 min (429, in memory, per process). Codes go through an
  `OtpSender`: the console sender exists only outside production; production without a provider
  → 503 `otp_not_configured`; provider failure → 502 `otp_send_failed`.
- **Tokens** expire after 90 days without use (sliding; `last_used_at` is refreshed at most once a
  minute). An expired token gets 401 `token_expired` and is deleted. `POST /v1/auth/logout`
  revokes only the calling token.
- **Account erase.** `DELETE /v1/me` removes, in one transaction: plan, sessions, unlock requests,
  invites, key-holder links both ways, entitlements, purchases, OTP codes for the contact, all
  tokens and the user row. Requests the user decided as someone's key holder stay with that owner,
  with `decided_by` cleared. A test scans every column of every table afterwards.
- **Plan.** `PUT` succeeds only when `version` = stored + 1 (first PUT = 1); otherwise
  409 `version_conflict` with `current` (the stored plan, or `null`). `app:` surfaces for
  protected packages (settings, package installers, Play Store, dialer/phone, emergency, Steady)
  → 400 `protected_app`. The body is stored as sent.
- **Key holder.** Invites are 6 characters from `A–Z 2–9` without `I O 0 1`, single use,
  24 h, case-insensitive. Own invite → 400; used → 410 `invite_used`; expired → 410
  `invite_expired`. Free owners may have **one** key holder: another accept → 409
  `keyholder_limit` and the invite stays unused (works after upgrading or removing the current
  link). Plus owners have no limit. Links have ids: `GET /v1/keyholder/links` (owner view),
  `GET /v1/keyholder/owners` (key-holder view), `DELETE /v1/keyholder/links/{linkId}` (either side;
  else 404). `GET /v1/keyholder/summary?ownerUserId=&weekStart=&tz=` gives a linked key holder
  the owner's weekly summary; anyone else gets 404.
- **Unlocks.** Creating a request needs a linked key holder (else 409 `no_keyholder`). Only a
  linked key holder may decide (403 otherwise); a second decision → 409. Approval minutes default
  to the owner's `plan.unlockPolicy.approvalMinutes`, else 15, max 240. The owner and linked key
  holders can poll; others get 404.
- **Weekly summary.** `?weekStart=YYYY-MM-DD[&tz=IANA]` (tz defaults to UTC). A session counts for
  the local date it starts on; `focusMinutes` is the rounded total; `blocks` is the session count;
  `days` the sorted distinct dates with a session.
- **Billing.** `POST /v1/billing/verify {productId, purchaseToken}` (`steady_plus_monthly` |
  `steady_plus_yearly`) checks the token through a `PurchaseVerifier` and sets the tier to `plus`.
  Selection: Play credentials → `GooglePlayVerifier` (androidpublisher
  `purchases.subscriptionsv2.get`; not exercised by tests); otherwise outside production the dev
  verifier accepts tokens starting with `test-`; production without credentials → 503
  `billing_not_configured`. Rejected → 400 `purchase_invalid`; store unreachable → 502
  `billing_unavailable`; a token already bound to another account → 409 `purchase_in_use`.
  Purchase tokens are stored hashed with their expiry.
- **Entitlements.** `free` → `[]`; `plus` → `unlimited_schedules, desktop_sync, notes_to_cards,
  task_breakdown`.
- **Migrations** are append-only and versioned with `PRAGMA user_version`; a database newer than
  the code refuses to open. Migration 2 adds `tokens.last_used_at` (back-filled from
  `created_at`), key-holder link ids and the `purchases` table; a test upgrades a v1 database.

## Not done yet (before launch)

- Downgrade on expiry/refund: Play Real-time Developer Notifications (Pub/Sub) and purchase
  acknowledgement. Today a verified purchase sets `plus` until changed.
- The OTP-verify limiter is in memory; run one instance or move it to shared storage.
- `HttpOtpSender` posts a generic JSON body; it needs a thin adapter for the chosen provider.
