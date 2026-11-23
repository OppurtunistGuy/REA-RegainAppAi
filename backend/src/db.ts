import { DatabaseSync } from "node:sqlite";

export type Db = DatabaseSync;

/**
 * Ordered list of migrations. Each entry runs once; `PRAGMA user_version`
 * records how many have been applied. Append only, never edit a shipped one.
 *
 * Privacy: nothing here stores screen contents. Sessions hold only times and
 * counts; unlock requests hold the reason the user typed for their key holder.
 */
const MIGRATIONS: string[] = [
  `
  CREATE TABLE users (
    id          TEXT PRIMARY KEY,
    contact     TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    created_at  INTEGER NOT NULL
  );

  CREATE TABLE otp_codes (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    contact     TEXT NOT NULL,
    code_hash   TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0,
    used        INTEGER NOT NULL DEFAULT 0
  );
  CREATE INDEX otp_codes_contact ON otp_codes(contact, created_at);

  CREATE TABLE tokens (
    token_hash  TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  INTEGER NOT NULL
  );

  CREATE TABLE plans (
    user_id     TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    version     INTEGER NOT NULL,
    body        TEXT NOT NULL,
    updated_at  INTEGER NOT NULL
  );

  CREATE TABLE keyholder_links (
    owner_user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    keyholder_user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at         INTEGER NOT NULL,
    PRIMARY KEY (owner_user_id, keyholder_user_id)
  );
  CREATE INDEX keyholder_links_kh ON keyholder_links(keyholder_user_id);

  CREATE TABLE invites (
    code           TEXT PRIMARY KEY,
    owner_user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at     INTEGER NOT NULL,
    expires_at     INTEGER NOT NULL,
    used_by        TEXT REFERENCES users(id) ON DELETE SET NULL,
    used_at        INTEGER
  );

  CREATE TABLE unlock_requests (
    id             TEXT PRIMARY KEY,
    owner_user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    surface_id     TEXT,
    reason         TEXT NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('pending','approved','denied')),
    created_at     INTEGER NOT NULL,
    decided_at     INTEGER,
    decided_by     TEXT REFERENCES users(id) ON DELETE SET NULL,
    until          INTEGER
  );
  CREATE INDEX unlock_requests_owner ON unlock_requests(owner_user_id, status);

  CREATE TABLE sessions (
    user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    id           TEXT NOT NULL,
    schedule_id  TEXT,
    start_ms     INTEGER NOT NULL,
    end_ms       INTEGER NOT NULL,
    intercepts   INTEGER NOT NULL,
    unlocks      INTEGER NOT NULL,
    PRIMARY KEY (user_id, id)
  );
  CREATE INDEX sessions_user_start ON sessions(user_id, start_ms);

  CREATE TABLE entitlements (
    user_id     TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    tier        TEXT NOT NULL CHECK (tier IN ('free','plus')),
    updated_at  INTEGER NOT NULL
  );
  `,
];

export function migrate(db: Db): void {
  const row = db.prepare("PRAGMA user_version").get() as { user_version: number };
  const current = row.user_version;
  for (let i = current; i < MIGRATIONS.length; i++) {
    db.exec("BEGIN");
    try {
      db.exec(MIGRATIONS[i]!);
      db.exec(`PRAGMA user_version = ${i + 1}`);
      db.exec("COMMIT");
    } catch (err) {
      db.exec("ROLLBACK");
      throw err;
    }
  }
}

/** Opens (or creates) a database and applies pending migrations. Use ":memory:" for tests. */
export function openDb(path = ":memory:"): Db {
  const db = new DatabaseSync(path);
  db.exec("PRAGMA foreign_keys = ON");
  if (path !== ":memory:") db.exec("PRAGMA journal_mode = WAL");
  migrate(db);
  return db;
}

export const SCHEMA_VERSION = MIGRATIONS.length;
