# Steady backend (Phase 9)

Small JSON API that syncs the rules Plan between phone and browser, pairs a key holder
(friend or parent) who approves emergency unlocks, stores aggregate focus sessions for the
weekly summary, serves the detector config and reports entitlements.

Stack: Node 22, TypeScript (strict), Hono + `@hono/node-server`, built-in `node:sqlite`
(no native deps), zod, vitest. The API is described in [`../shared/api/openapi.yaml`](../shared/api/openapi.yaml)
and fixed by `docs/CONTRACTS.md` (Phase 9).

## Run

```sh
npm ci
npm run dev        # tsx watch, http://localhost:8787
npm run build      # tsc → dist/
npm start          # node dist/server.js
npm test           # vitest run (in-memory SQLite)
npm run typecheck  # tsc --noEmit (src + tests)
```

Quick check:

```sh
curl -X POST localhost:8787/v1/auth/otp/start -H 'content-type: application/json' -d '{"contact":"me@example.com"}'
# the code is printed to the server console
curl -X POST localhost:8787/v1/auth/otp/verify -H 'content-type: application/json' \
  -d '{"contact":"me@example.com","code":"123456"}'
```

`node:sqlite` needs no flag on Node ≥ 22.13 but prints an `ExperimentalWarning`; the scripts
and the vitest config pass `--disable-warning=ExperimentalWarning`. On Node 22.5–22.12 add
`--experimental-sqlite` to `NODE_OPTIONS`.

## Environment variables

| Name | Default | Meaning |
|------|---------|---------|
| `PORT` | `8787` | HTTP port |
| `DATABASE_PATH` | `steady.db` | SQLite file (`:memory:` for throwaway) |
| `DETECTORS_PATH` | `../shared/detectors/detectors.v1.json` | Detector config served by `GET /v1/detectors` |
| `CORS_ORIGINS` | – | Extra comma-separated exact origins. `chrome-extension://*` is always allowed. |
| `NODE_ENV` | – | `test` makes every OTP code `000000` |

## Layout

```
src/app.ts           createApp({db, clock, otpSender, detectorsPath?, fixedOtp?, corsOrigins?})
src/server.ts        entry point
src/db.ts            openDb() + versioned migrations (PRAGMA user_version)
src/schemas.ts       zod schemas; every body is .strict()
src/summary.ts       weekly summary math (pure)
src/entitlements.ts  tiers, features, setTier() (for tests and the future billing webhook)
src/errors.ts        ApiError → {error: {code, message}}
test/                vitest suites, one per area, plus an OpenAPI ↔ routes check
```

## Behaviour notes

- **Privacy.** Unknown body fields → 400 `invalid_body`. Error messages name the offending
  field but never echo its value. Sessions store only times and counts. Bodies over 64 KB → 413.
- **Auth.** OTP codes (6 digits, 10 min, single use, locked after 5 wrong tries) and bearer
  tokens are stored as SHA-256 hashes. OTP start is limited to 5 per contact per hour (429).
  Contacts are trimmed and lower-cased. Tokens do not expire yet.
- **Plan.** `PUT` succeeds only when `version` = stored + 1 (first PUT = 1); otherwise
  409 `version_conflict` with `current` (the stored plan, or `null`). The body is stored as sent.
- **Key holder.** Invites are 6 characters from `A–Z 2–9` without `I O 0 1`, single use,
  24 h. Codes are case-insensitive. Accepting your own invite → 400; used → 410 `invite_used`;
  expired → 410 `invite_expired`. An owner may have several key holders.
- **Unlocks.** Creating a request needs a linked key holder (else 409 `no_keyholder`). Only a
  linked key holder may decide (403 otherwise); a second decision → 409. Approval minutes default
  to the owner's `plan.unlockPolicy.approvalMinutes`, else 15, max 240. The owner and linked key
  holders can poll; others get 404.
- **Weekly summary.** `?weekStart=YYYY-MM-DD[&tz=IANA]` (tz defaults to UTC). A session counts for
  the local date it starts on; `focusMinutes` is the rounded total; `blocks` is the session count;
  `days` the sorted distinct dates with a session.
- **Entitlements.** `free` → `[]`; `plus` → `unlimited_schedules, desktop_sync, notes_to_cards,
  task_breakdown`. No HTTP route changes the tier.
