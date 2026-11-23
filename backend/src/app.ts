import { createHash, randomBytes, randomInt, randomUUID, timingSafeEqual } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { Hono, type Context, type MiddlewareHandler } from "hono";
import { bodyLimit } from "hono/body-limit";
import { cors } from "hono/cors";
import type { z } from "zod";
import type { Db } from "./db.js";
import { getEntitlement } from "./entitlements.js";
import {
  ApiError,
  badRequest,
  conflict,
  errorBody,
  forbidden,
  gone,
  notFound,
  tooMany,
  unauthorized,
} from "./errors.js";
import {
  DecisionSchema,
  EmptyBodySchema,
  FocusSessionSchema,
  OtpStartSchema,
  OtpVerifySchema,
  PlanSchema,
  UnlockRequestSchema,
  type Plan,
} from "./schemas.js";
import { isValidTimeZone, parseDate, queryWindow, weeklySummary } from "./summary.js";

export interface Clock {
  now(): Date;
}
export const systemClock: Clock = { now: () => new Date() };

export type OtpSender = (contact: string, code: string) => void | Promise<void>;
export const consoleOtpSender: OtpSender = (contact, code) => {
  console.log(`[otp] code for ${contact}: ${code}`);
};

export interface AppOptions {
  db: Db;
  clock?: Clock;
  otpSender?: OtpSender;
  /** Path to the detector config JSON. Defaults to ../shared/detectors/detectors.v1.json. */
  detectorsPath?: string;
  /** When true every OTP is "000000". Defaults to NODE_ENV === "test". */
  fixedOtp?: boolean;
  /** Extra allowed CORS origins (exact match). chrome-extension://* is always allowed. */
  corsOrigins?: string[];
}

export const DEFAULT_DETECTORS_PATH = fileURLToPath(
  new URL("../../shared/detectors/detectors.v1.json", import.meta.url),
);

export const OTP_TTL_MS = 10 * 60_000;
export const OTP_MAX_PER_HOUR = 5;
export const OTP_MAX_ATTEMPTS = 5;
export const INVITE_TTL_MS = 24 * 60 * 60_000;
export const DEFAULT_UNLOCK_MINUTES = 15;
export const MAX_BODY_BYTES = 64 * 1024;

const INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I, O, 0, 1

type Env = { Variables: { userId: string } };

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");
const iso = (ms: number) => new Date(ms).toISOString();
const normalizeContact = (c: string) => c.trim().toLowerCase();

function defaultName(contact: string): string {
  const at = contact.indexOf("@");
  if (at > 0) return contact.slice(0, Math.min(at, 60));
  return `•••${contact.slice(-4)}`;
}

function safeEqualHex(a: string, b: string): boolean {
  const ab = Buffer.from(a, "hex");
  const bb = Buffer.from(b, "hex");
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

function describeZodError(err: z.ZodError): string {
  // Paths and messages only; never echo submitted values.
  return err.issues
    .slice(0, 5)
    .map((i) => {
      const path = i.path.length ? i.path.join(".") : "(body)";
      if (i.code === "unrecognized_keys") return `${path}: unknown field(s) ${i.keys.join(", ")}`;
      return `${path}: ${i.message}`;
    })
    .join("; ");
}

async function readJson(c: Context, allowEmpty: boolean): Promise<unknown> {
  const text = await c.req.text();
  if (text.trim() === "") {
    if (allowEmpty) return {};
    throw badRequest("invalid_json", "Request body must be JSON");
  }
  try {
    return JSON.parse(text);
  } catch {
    throw badRequest("invalid_json", "Request body must be JSON");
  }
}

async function parseBody<S extends z.ZodType>(
  c: Context,
  schema: S,
  allowEmpty = false,
): Promise<z.infer<S>> {
  const raw = await readJson(c, allowEmpty);
  const result = schema.safeParse(raw);
  if (!result.success) throw badRequest("invalid_body", describeZodError(result.error));
  return result.data;
}

interface UnlockRow {
  id: string;
  owner_user_id: string;
  surface_id: string | null;
  reason: string;
  status: "pending" | "approved" | "denied";
  created_at: number;
  until: number | null;
}

function unlockStatus(r: UnlockRow) {
  return {
    id: r.id,
    status: r.status,
    ...(r.until !== null ? { until: iso(r.until) } : {}),
  };
}

export function createApp(opts: AppOptions) {
  const db = opts.db;
  const clock = opts.clock ?? systemClock;
  const otpSender = opts.otpSender ?? consoleOtpSender;
  const detectorsPath = opts.detectorsPath ?? DEFAULT_DETECTORS_PATH;
  const fixedOtp = opts.fixedOtp ?? process.env.NODE_ENV === "test";
  const extraOrigins = new Set(opts.corsOrigins ?? []);
  const nowMs = () => clock.now().getTime();

  const app = new Hono<Env>();

  app.use(
    "*",
    cors({
      origin: (origin) =>
        origin && (/^chrome-extension:\/\/[a-z0-9]+$/i.test(origin) || extraOrigins.has(origin))
          ? origin
          : null,
      allowMethods: ["GET", "POST", "PUT", "OPTIONS"],
      allowHeaders: ["Authorization", "Content-Type"],
      maxAge: 600,
    }),
  );

  app.use(
    "*",
    bodyLimit({
      maxSize: MAX_BODY_BYTES,
      onError: (c) => c.json(errorBody("body_too_large", "Request body too large"), 413),
    }),
  );

  app.onError((err, c) => {
    if (err instanceof ApiError) {
      return c.json(errorBody(err.code, err.message, err.extra), err.status);
    }
    console.error(err);
    return c.json(errorBody("internal", "Internal server error"), 500);
  });

  app.notFound((c) => c.json(errorBody("not_found", "Not found"), 404));

  const requireAuth: MiddlewareHandler<Env> = async (c, next) => {
    const header = c.req.header("Authorization") ?? "";
    const m = /^Bearer ([A-Za-z0-9_-]{20,200})$/.exec(header);
    if (!m) throw unauthorized();
    const row = db.prepare("SELECT user_id FROM tokens WHERE token_hash = ?").get(sha256(m[1]!)) as
      | { user_id: string }
      | undefined;
    if (!row) throw unauthorized();
    c.set("userId", row.user_id);
    await next();
  };

  const v1 = new Hono<Env>();

  v1.get("/health", (c) => c.json({ ok: true }));

  // ---------------- Auth ----------------

  v1.post("/auth/otp/start", async (c) => {
    const body = await parseBody(c, OtpStartSchema);
    const contact = normalizeContact(body.contact);
    const now = nowMs();
    const recent = db
      .prepare("SELECT COUNT(*) AS n FROM otp_codes WHERE contact = ? AND created_at > ?")
      .get(contact, now - 60 * 60_000) as { n: number };
    if (recent.n >= OTP_MAX_PER_HOUR) throw tooMany("Too many codes requested. Try again later.");

    const code = fixedOtp ? "000000" : String(randomInt(0, 1_000_000)).padStart(6, "0");
    db.prepare(
      "INSERT INTO otp_codes (contact, code_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
    ).run(contact, sha256(`${contact}:${code}`), now, now + OTP_TTL_MS);
    await otpSender(contact, code);
    return c.body(null, 204);
  });

  v1.post("/auth/otp/verify", async (c) => {
    const body = await parseBody(c, OtpVerifySchema);
    const contact = normalizeContact(body.contact);
    const now = nowMs();
    const otp = db
      .prepare(
        `SELECT id, code_hash, attempts FROM otp_codes
         WHERE contact = ? AND used = 0 AND expires_at > ?
         ORDER BY created_at DESC, id DESC LIMIT 1`,
      )
      .get(contact, now) as { id: number; code_hash: string; attempts: number } | undefined;
    if (!otp || otp.attempts >= OTP_MAX_ATTEMPTS) {
      throw new ApiError(401, "invalid_code", "Code is invalid or expired");
    }
    if (!safeEqualHex(otp.code_hash, sha256(`${contact}:${body.code}`))) {
      db.prepare("UPDATE otp_codes SET attempts = attempts + 1 WHERE id = ?").run(otp.id);
      throw new ApiError(401, "invalid_code", "Code is invalid or expired");
    }
    db.prepare("UPDATE otp_codes SET used = 1 WHERE id = ?").run(otp.id);

    let user = db.prepare("SELECT id FROM users WHERE contact = ?").get(contact) as
      | { id: string }
      | undefined;
    if (!user) {
      user = { id: randomUUID() };
      db.prepare("INSERT INTO users (id, contact, name, created_at) VALUES (?, ?, ?, ?)").run(
        user.id,
        contact,
        body.name ?? defaultName(contact),
        now,
      );
    } else if (body.name) {
      db.prepare("UPDATE users SET name = ? WHERE id = ?").run(body.name, user.id);
    }

    const token = randomBytes(32).toString("base64url");
    db.prepare("INSERT INTO tokens (token_hash, user_id, created_at) VALUES (?, ?, ?)").run(
      sha256(token),
      user.id,
      now,
    );
    return c.json({ token, userId: user.id });
  });

  // ---------------- Plan ----------------

  const loadPlan = (userId: string): Plan | null => {
    const row = db.prepare("SELECT body FROM plans WHERE user_id = ?").get(userId) as
      | { body: string }
      | undefined;
    return row ? (JSON.parse(row.body) as Plan) : null;
  };

  v1.get("/plan", requireAuth, (c) => {
    const plan = loadPlan(c.get("userId"));
    if (!plan) throw notFound("No plan stored yet");
    return c.json(plan);
  });

  v1.put("/plan", requireAuth, async (c) => {
    const userId = c.get("userId");
    const plan = await parseBody(c, PlanSchema);
    db.exec("BEGIN IMMEDIATE");
    try {
      const current = loadPlan(userId);
      const expected = (current?.version ?? 0) + 1;
      if (plan.version !== expected) {
        db.exec("ROLLBACK");
        throw conflict(
          "version_conflict",
          `Plan version must be ${expected}`,
          { current },
        );
      }
      db.prepare(
        `INSERT INTO plans (user_id, version, body, updated_at) VALUES (?, ?, ?, ?)
         ON CONFLICT(user_id) DO UPDATE SET version = excluded.version, body = excluded.body,
           updated_at = excluded.updated_at`,
      ).run(userId, plan.version, JSON.stringify(plan), nowMs());
      db.exec("COMMIT");
    } catch (err) {
      if (db.isTransaction) db.exec("ROLLBACK");
      throw err;
    }
    return c.json(plan);
  });

  // ---------------- Detectors ----------------

  v1.get("/detectors", async (c) => {
    let text: string;
    try {
      text = await readFile(detectorsPath, "utf8");
    } catch {
      throw new ApiError(503, "detectors_unavailable", "Detector config is not available");
    }
    return c.json(JSON.parse(text) as unknown);
  });

  // ---------------- Key holder ----------------

  v1.post("/keyholder/invites", requireAuth, async (c) => {
    await parseBody(c, EmptyBodySchema, true);
    const userId = c.get("userId");
    const now = nowMs();
    const expiresAt = now + INVITE_TTL_MS;
    for (let attempt = 0; attempt < 10; attempt++) {
      let code = "";
      for (let i = 0; i < 6; i++) code += INVITE_ALPHABET[randomInt(0, INVITE_ALPHABET.length)];
      const exists = db.prepare("SELECT 1 FROM invites WHERE code = ?").get(code);
      if (exists) continue;
      db.prepare(
        "INSERT INTO invites (code, owner_user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
      ).run(code, userId, now, expiresAt);
      return c.json({ code, expiresAt: iso(expiresAt) }, 201);
    }
    throw new ApiError(503, "invite_unavailable", "Could not allocate an invite code");
  });

  v1.post("/keyholder/invites/:code/accept", requireAuth, async (c) => {
    await parseBody(c, EmptyBodySchema, true);
    const userId = c.get("userId");
    const code = c.req.param("code").toUpperCase();
    if (!/^[A-Z0-9]{6}$/.test(code)) throw notFound("Invite not found");
    const invite = db
      .prepare("SELECT owner_user_id, expires_at, used_at FROM invites WHERE code = ?")
      .get(code) as { owner_user_id: string; expires_at: number; used_at: number | null } | undefined;
    if (!invite) throw notFound("Invite not found");
    if (invite.used_at !== null) throw gone("invite_used", "This invite was already used");
    const now = nowMs();
    if (invite.expires_at <= now) throw gone("invite_expired", "This invite has expired");
    if (invite.owner_user_id === userId) {
      throw badRequest("self_invite", "You cannot hold your own key");
    }
    db.exec("BEGIN IMMEDIATE");
    try {
      db.prepare("UPDATE invites SET used_by = ?, used_at = ? WHERE code = ?").run(userId, now, code);
      db.prepare(
        `INSERT INTO keyholder_links (owner_user_id, keyholder_user_id, created_at) VALUES (?, ?, ?)
         ON CONFLICT DO NOTHING`,
      ).run(invite.owner_user_id, userId, now);
      db.exec("COMMIT");
    } catch (err) {
      if (db.isTransaction) db.exec("ROLLBACK");
      throw err;
    }
    const owner = db.prepare("SELECT name FROM users WHERE id = ?").get(invite.owner_user_id) as {
      name: string;
    };
    return c.json({ ownerUserId: invite.owner_user_id, ownerName: owner.name });
  });

  v1.get("/keyholder/requests", requireAuth, (c) => {
    const rows = db
      .prepare(
        `SELECT r.id, r.reason, r.surface_id, r.created_at, u.name AS owner_name
         FROM unlock_requests r
         JOIN keyholder_links l ON l.owner_user_id = r.owner_user_id AND l.keyholder_user_id = ?
         JOIN users u ON u.id = r.owner_user_id
         WHERE r.status = 'pending'
         ORDER BY r.created_at ASC, r.id ASC`,
      )
      .all(c.get("userId")) as {
      id: string;
      reason: string;
      surface_id: string | null;
      created_at: number;
      owner_name: string;
    }[];
    return c.json(
      rows.map((r) => ({
        id: r.id,
        ownerName: r.owner_name,
        reason: r.reason,
        surfaceId: r.surface_id,
        createdAt: iso(r.created_at),
      })),
    );
  });

  // ---------------- Unlock requests ----------------

  const isKeyHolderFor = (keyholderId: string, ownerId: string) =>
    db
      .prepare("SELECT 1 FROM keyholder_links WHERE owner_user_id = ? AND keyholder_user_id = ?")
      .get(ownerId, keyholderId) !== undefined;

  const loadRequest = (id: string) =>
    db.prepare("SELECT * FROM unlock_requests WHERE id = ?").get(id) as UnlockRow | undefined;

  v1.post("/unlock-requests", requireAuth, async (c) => {
    const body = await parseBody(c, UnlockRequestSchema);
    const userId = c.get("userId");
    const hasKeyHolder = db
      .prepare("SELECT 1 FROM keyholder_links WHERE owner_user_id = ? LIMIT 1")
      .get(userId);
    if (!hasKeyHolder) throw conflict("no_keyholder", "No key holder is linked to this account");
    const id = randomUUID();
    db.prepare(
      `INSERT INTO unlock_requests (id, owner_user_id, surface_id, reason, status, created_at)
       VALUES (?, ?, ?, ?, 'pending', ?)`,
    ).run(id, userId, body.surfaceId, body.reason, nowMs());
    return c.json({ id, status: "pending" as const }, 201);
  });

  v1.get("/unlock-requests/:id", requireAuth, (c) => {
    const userId = c.get("userId");
    const r = loadRequest(c.req.param("id"));
    if (!r || (r.owner_user_id !== userId && !isKeyHolderFor(userId, r.owner_user_id))) {
      throw notFound("Unlock request not found");
    }
    return c.json(unlockStatus(r));
  });

  v1.post("/unlock-requests/:id/decision", requireAuth, async (c) => {
    const body = await parseBody(c, DecisionSchema);
    const userId = c.get("userId");
    const r = loadRequest(c.req.param("id"));
    if (!r) throw notFound("Unlock request not found");
    if (!isKeyHolderFor(userId, r.owner_user_id)) {
      throw forbidden("Only the linked key holder can decide this request");
    }
    if (r.status !== "pending") throw conflict("already_decided", "This request was already decided");

    const now = nowMs();
    const status = body.approve ? "approved" : "denied";
    let until: number | null = null;
    if (body.approve) {
      const policyMinutes = loadPlan(r.owner_user_id)?.unlockPolicy?.approvalMinutes;
      const minutes = body.minutes ?? policyMinutes ?? DEFAULT_UNLOCK_MINUTES;
      until = now + minutes * 60_000;
    }
    const res = db
      .prepare(
        `UPDATE unlock_requests SET status = ?, decided_at = ?, decided_by = ?, until = ?
         WHERE id = ? AND status = 'pending'`,
      )
      .run(status, now, userId, until, r.id);
    if (res.changes === 0) throw conflict("already_decided", "This request was already decided");
    return c.json(unlockStatus({ ...r, status, until }));
  });

  // ---------------- Sessions / summary ----------------

  v1.post("/sessions", requireAuth, async (c) => {
    const s = await parseBody(c, FocusSessionSchema);
    db.prepare(
      `INSERT INTO sessions (user_id, id, schedule_id, start_ms, end_ms, intercepts, unlocks)
       VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user_id, id) DO NOTHING`,
    ).run(
      c.get("userId"),
      s.id,
      s.scheduleId,
      Date.parse(s.start),
      Date.parse(s.end),
      s.intercepts,
      s.unlocks,
    );
    return c.body(null, 204);
  });

  v1.get("/summary/weekly", requireAuth, (c) => {
    const weekStart = c.req.query("weekStart") ?? "";
    const weekStartMs = parseDate(weekStart);
    if (weekStartMs === null) throw badRequest("invalid_query", "weekStart must be YYYY-MM-DD");
    const tz = c.req.query("tz") ?? "UTC";
    if (!isValidTimeZone(tz)) throw badRequest("invalid_query", "tz must be an IANA time zone");
    const { fromMs, toMs } = queryWindow(weekStartMs);
    const rows = db
      .prepare(
        `SELECT start_ms AS startMs, end_ms AS endMs, intercepts, unlocks FROM sessions
         WHERE user_id = ? AND start_ms >= ? AND start_ms < ?`,
      )
      .all(c.get("userId"), fromMs, toMs) as {
      startMs: number;
      endMs: number;
      intercepts: number;
      unlocks: number;
    }[];
    return c.json(weeklySummary(rows, weekStart, tz));
  });

  // ---------------- Entitlements ----------------

  v1.get("/entitlements", requireAuth, (c) => c.json(getEntitlement(db, c.get("userId"))));

  app.route("/v1", v1);
  return app;
}
