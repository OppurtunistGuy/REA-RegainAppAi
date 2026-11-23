import { createHash, randomBytes, randomInt, randomUUID, timingSafeEqual } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { Hono, type Context, type MiddlewareHandler } from "hono";
import { bodyLimit } from "hono/body-limit";
import { cors } from "hono/cors";
import { routePath } from "hono/route";
import { secureHeaders } from "hono/secure-headers";
import type { z } from "zod";
import { DevPurchaseVerifier, verifierFromEnv, type PurchaseVerifier } from "./billing.js";
import { otpSenderFromEnv, type OtpSendFn, type OtpSender } from "./otp.js";
import type { Db } from "./db.js";
import { getEntitlement, getTier, setTier } from "./entitlements.js";
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
  BillingVerifySchema,
  DecisionSchema,
  EmptyBodySchema,
  FocusSessionSchema,
  OtpStartSchema,
  OtpVerifySchema,
  PlanSchema,
  PROTECTED_PACKAGES,
  UnlockRequestSchema,
  type Plan,
} from "./schemas.js";
import { isValidTimeZone, parseDate, queryWindow, weeklySummary } from "./summary.js";

export interface Clock {
  now(): Date;
}
export const systemClock: Clock = { now: () => new Date() };

export type { OtpSender } from "./otp.js";

export interface AppOptions {
  db: Db;
  clock?: Clock;
  /**
   * Delivers OTP codes. Defaults to otpSenderFromEnv(): provider configured → HTTP sender;
   * else the console sender outside production; `null` (production, no provider) → OTP start 503.
   */
  otpSender?: OtpSender | OtpSendFn | null;
  /** Path to the detector config JSON. Defaults to ../shared/detectors/detectors.v1.json. */
  detectorsPath?: string;
  /** When true every OTP is "000000". Defaults to NODE_ENV === "test". */
  fixedOtp?: boolean;
  /** Extra allowed CORS origins (exact match). chrome-extension://* is always allowed. */
  corsOrigins?: string[];
  /**
   * Store verifier for POST /v1/billing/verify. `null` = not configured (503).
   * Defaults to verifierFromEnv(): Play credentials -> Google Play; else dev
   * ("test-" tokens) outside production; else null.
   */
  purchaseVerifier?: PurchaseVerifier | null;
  /** One line per request (method, route pattern, status, ms, request id). Never bodies. */
  log?: (line: string) => void;
  /** Use the first X-Forwarded-For hop as the client IP (behind a reverse proxy). */
  trustProxy?: boolean;
  /** OTP verify attempts allowed per client IP per window. Default 30 per 15 min. */
  otpVerifyPerIp?: number;
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
/** Sliding token lifetime: a token unused for this long is expired. */
export const TOKEN_IDLE_MS = 90 * 86_400_000;
/** last_used_at is refreshed at most this often (saves a write per request). */
export const TOKEN_TOUCH_MS = 60_000;
export const OTP_VERIFY_WINDOW_MS = 15 * 60_000;
export const OTP_VERIFY_PER_IP = 30;
/** Free tier: key holders per owner. Plus is unlimited. */
export const FREE_KEYHOLDER_LIMIT = 1;

const INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I, O, 0, 1

type Env = { Variables: { userId: string; tokenHash: string; requestId: string } };

function clientIp(c: Context, trustProxy: boolean): string {
  if (trustProxy) {
    const xff = c.req.header("x-forwarded-for")?.split(",")[0]?.trim();
    if (xff) return xff;
  }
  const env = c.env as { incoming?: { socket?: { remoteAddress?: string } } } | undefined;
  return env?.incoming?.socket?.remoteAddress ?? "unknown";
}

/** Sliding-window limiter kept in memory (single process). */
class RateLimiter {
  private hits = new Map<string, number[]>();
  constructor(
    private readonly max: number,
    private readonly windowMs: number,
  ) {}
  /** Records a hit; returns false when over the limit. */
  hit(key: string, now: number): boolean {
    const since = now - this.windowMs;
    const list = (this.hits.get(key) ?? []).filter((t) => t > since);
    if (list.length >= this.max) {
      this.hits.set(key, list);
      return false;
    }
    list.push(now);
    this.hits.set(key, list);
    if (this.hits.size > 10_000) {
      for (const [k, v] of this.hits) if (!v.some((t) => t > since)) this.hits.delete(k);
    }
    return true;
  }
}

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");
const iso = (ms: number) => new Date(ms).toISOString();
const normalizeContact = (c: string) => c.trim().toLowerCase();

/** Shown to key holders when the user gave no name. Never derived from the email or phone. */
export const DEFAULT_DISPLAY_NAME = "Your friend";

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
  const rawSender = opts.otpSender !== undefined ? opts.otpSender : otpSenderFromEnv();
  const otpSender: OtpSender | null =
    typeof rawSender === "function" ? { name: "custom", send: rawSender } : rawSender;
  const detectorsPath = opts.detectorsPath ?? DEFAULT_DETECTORS_PATH;
  const fixedOtp = opts.fixedOtp ?? process.env.NODE_ENV === "test";
  const extraOrigins = new Set(opts.corsOrigins ?? []);
  const nowMs = () => clock.now().getTime();
  const purchaseVerifier =
    opts.purchaseVerifier !== undefined
      ? opts.purchaseVerifier
      : (() => {
          const v = verifierFromEnv();
          return v instanceof DevPurchaseVerifier ? new DevPurchaseVerifier(() => clock.now()) : v;
        })();
  const log =
    opts.log ?? (process.env.NODE_ENV === "test" ? () => {} : (line: string) => console.log(line));
  const trustProxy = opts.trustProxy ?? false;
  const verifyLimiter = new RateLimiter(opts.otpVerifyPerIp ?? OTP_VERIFY_PER_IP, OTP_VERIFY_WINDOW_MS);

  const app = new Hono<Env>();

  // Request id + access log. Logs the route pattern (not the raw path, which can hold invite
  // codes or ids) and never request or response bodies.
  app.use("*", async (c, next) => {
    const incoming = c.req.header("x-request-id");
    const rid = incoming && /^[A-Za-z0-9._-]{8,64}$/.test(incoming) ? incoming : randomUUID();
    c.set("requestId", rid);
    const started = performance.now();
    await next();
    c.header("X-Request-Id", rid);
    const route = routePath(c, -1);
    const shown = route === "*" || route === "/*" || route === "" ? "(unmatched)" : route;
    log(
      `${c.req.method} ${shown} ${c.res.status} ${Math.round(performance.now() - started)}ms rid=${rid}`,
    );
  });

  app.use(
    "*",
    secureHeaders({
      // JSON API: nothing should ever be framed, sniffed or embedded.
      contentSecurityPolicy: { defaultSrc: ["'none'"], frameAncestors: ["'none'"] },
      crossOriginResourcePolicy: "same-site",
      referrerPolicy: "no-referrer",
    }),
  );
  app.use("*", async (c, next) => {
    await next();
    if (!c.res.headers.has("Cache-Control")) c.header("Cache-Control", "no-store");
  });

  app.use(
    "*",
    cors({
      origin: (origin) =>
        origin && (/^chrome-extension:\/\/[a-z0-9]+$/i.test(origin) || extraOrigins.has(origin))
          ? origin
          : null,
      allowMethods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
      exposeHeaders: ["X-Request-Id"],
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
    console.error(`[error] rid=${c.get("requestId") ?? "-"}`, err);
    return c.json(errorBody("internal", "Internal server error"), 500);
  });

  app.notFound((c) => c.json(errorBody("not_found", "Not found"), 404));

  const requireAuth: MiddlewareHandler<Env> = async (c, next) => {
    const header = c.req.header("Authorization") ?? "";
    const m = /^Bearer ([A-Za-z0-9_-]{20,200})$/.exec(header);
    if (!m) throw unauthorized();
    const tokenHash = sha256(m[1]!);
    const row = db
      .prepare("SELECT user_id, created_at, last_used_at FROM tokens WHERE token_hash = ?")
      .get(tokenHash) as { user_id: string; created_at: number; last_used_at: number | null } | undefined;
    if (!row) throw unauthorized();
    const now = nowMs();
    const lastUsed = row.last_used_at ?? row.created_at;
    if (now - lastUsed >= TOKEN_IDLE_MS) {
      db.prepare("DELETE FROM tokens WHERE token_hash = ?").run(tokenHash);
      throw new ApiError(401, "token_expired", "Session expired. Please sign in again.");
    }
    if (now - lastUsed >= TOKEN_TOUCH_MS) {
      db.prepare("UPDATE tokens SET last_used_at = ? WHERE token_hash = ?").run(now, tokenHash);
    }
    c.set("userId", row.user_id);
    c.set("tokenHash", tokenHash);
    await next();
  };

  function summaryFor(userId: string, weekStartQ: string | undefined, tzQ: string | undefined) {
    const weekStart = weekStartQ ?? "";
    const weekStartMs = parseDate(weekStart);
    if (weekStartMs === null) throw badRequest("invalid_query", "weekStart must be YYYY-MM-DD");
    const tz = tzQ ?? "UTC";
    if (!isValidTimeZone(tz)) throw badRequest("invalid_query", "tz must be an IANA time zone");
    const { fromMs, toMs } = queryWindow(weekStartMs);
    const rows = db
      .prepare(
        `SELECT start_ms AS startMs, end_ms AS endMs, intercepts, unlocks FROM sessions
         WHERE user_id = ? AND start_ms >= ? AND start_ms < ?`,
      )
      .all(userId, fromMs, toMs) as {
      startMs: number;
      endMs: number;
      intercepts: number;
      unlocks: number;
    }[];
    return weeklySummary(rows, weekStart, tz);
  }

  /**
   * Erases everything stored about a user in one transaction. Explicit deletes (rather than
   * relying on ON DELETE CASCADE) so the list is reviewable and survives schema changes.
   */
  function eraseUser(userId: string): void {
    const user = db.prepare("SELECT contact FROM users WHERE id = ?").get(userId) as
      | { contact: string }
      | undefined;
    db.exec("BEGIN IMMEDIATE");
    try {
      db.prepare("DELETE FROM sessions WHERE user_id = ?").run(userId);
      db.prepare("DELETE FROM plans WHERE user_id = ?").run(userId);
      db.prepare("DELETE FROM unlock_requests WHERE owner_user_id = ?").run(userId);
      // Requests this user decided as someone's key holder stay with their owner, unattributed.
      db.prepare("UPDATE unlock_requests SET decided_by = NULL WHERE decided_by = ?").run(userId);
      db.prepare("DELETE FROM invites WHERE owner_user_id = ?").run(userId);
      db.prepare("UPDATE invites SET used_by = NULL WHERE used_by = ?").run(userId);
      db.prepare(
        "DELETE FROM keyholder_links WHERE owner_user_id = ? OR keyholder_user_id = ?",
      ).run(userId, userId);
      db.prepare("DELETE FROM entitlements WHERE user_id = ?").run(userId);
      db.prepare("DELETE FROM purchases WHERE user_id = ?").run(userId);
      db.prepare("DELETE FROM tokens WHERE user_id = ?").run(userId);
      if (user) db.prepare("DELETE FROM otp_codes WHERE contact = ?").run(user.contact);
      db.prepare("DELETE FROM users WHERE id = ?").run(userId);
      db.exec("COMMIT");
    } catch (err) {
      if (db.isTransaction) db.exec("ROLLBACK");
      throw err;
    }
  }

  const v1 = new Hono<Env>();

  v1.get("/health", (c) => c.json({ ok: true }));

  // ---------------- Auth ----------------

  v1.post("/auth/otp/start", async (c) => {
    const body = await parseBody(c, OtpStartSchema);
    if (!otpSender) {
      throw new ApiError(503, "otp_not_configured", "Sign-in codes cannot be sent right now");
    }
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
    try {
      await otpSender.send(contact, code);
    } catch (err) {
      console.error(
        `[otp] delivery via ${otpSender.name} failed rid=${c.get("requestId") ?? "-"}:`,
        err instanceof Error ? err.message : "error",
      );
      throw new ApiError(502, "otp_send_failed", "The code could not be sent. Try again.");
    }
    return c.body(null, 204);
  });

  v1.post("/auth/otp/verify", async (c) => {
    if (!verifyLimiter.hit(clientIp(c, trustProxy), nowMs())) {
      throw tooMany("Too many sign-in attempts. Try again later.");
    }
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
        body.name ?? DEFAULT_DISPLAY_NAME,
        now,
      );
    } else if (body.name) {
      db.prepare("UPDATE users SET name = ? WHERE id = ?").run(body.name, user.id);
    }

    const token = randomBytes(32).toString("base64url");
    db.prepare(
      "INSERT INTO tokens (token_hash, user_id, created_at, last_used_at) VALUES (?, ?, ?, ?)",
    ).run(sha256(token), user.id, now, now);
    return c.json({ token, userId: user.id });
  });

  v1.post("/auth/logout", requireAuth, async (c) => {
    await parseBody(c, EmptyBodySchema, true);
    db.prepare("DELETE FROM tokens WHERE token_hash = ?").run(c.get("tokenHash"));
    return c.body(null, 204);
  });

  // ---------------- Account ----------------

  v1.delete("/me", requireAuth, (c) => {
    eraseUser(c.get("userId"));
    return c.body(null, 204);
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
    const protectedApp = plan.distractions.find(
      (d) => d.id.startsWith("app:") && PROTECTED_PACKAGES.has(d.id.slice(4)),
    );
    if (protectedApp) {
      throw badRequest(
        "protected_app",
        `${protectedApp.id.slice(4)} keeps the phone safe to use and cannot be blocked`,
      );
    }
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

  function isKeyHolderFor(keyholderId: string, ownerId: string): boolean {
    return (
      db
        .prepare("SELECT 1 FROM keyholder_links WHERE owner_user_id = ? AND keyholder_user_id = ?")
        .get(ownerId, keyholderId) !== undefined
    );
  }

  v1.get("/keyholder/links", requireAuth, (c) => {
    const rows = db
      .prepare(
        `SELECT l.id, l.keyholder_user_id, l.created_at, u.name FROM keyholder_links l
         JOIN users u ON u.id = l.keyholder_user_id
         WHERE l.owner_user_id = ? ORDER BY l.created_at ASC, l.id ASC`,
      )
      .all(c.get("userId")) as { id: string; keyholder_user_id: string; created_at: number; name: string }[];
    return c.json(
      rows.map((r) => ({
        linkId: r.id,
        keyHolderUserId: r.keyholder_user_id,
        keyHolderName: r.name,
        createdAt: iso(r.created_at),
      })),
    );
  });

  v1.get("/keyholder/owners", requireAuth, (c) => {
    const rows = db
      .prepare(
        `SELECT l.id, l.owner_user_id, l.created_at, u.name FROM keyholder_links l
         JOIN users u ON u.id = l.owner_user_id
         WHERE l.keyholder_user_id = ? ORDER BY l.created_at ASC, l.id ASC`,
      )
      .all(c.get("userId")) as { id: string; owner_user_id: string; created_at: number; name: string }[];
    return c.json(
      rows.map((r) => ({
        linkId: r.id,
        ownerUserId: r.owner_user_id,
        ownerName: r.name,
        createdAt: iso(r.created_at),
      })),
    );
  });

  v1.delete("/keyholder/links/:linkId", requireAuth, (c) => {
    const userId = c.get("userId");
    const res = db
      .prepare(
        "DELETE FROM keyholder_links WHERE id = ? AND (owner_user_id = ? OR keyholder_user_id = ?)",
      )
      .run(c.req.param("linkId"), userId, userId);
    if (res.changes === 0) throw notFound("Link not found");
    return c.body(null, 204);
  });

  v1.get("/keyholder/summary", requireAuth, (c) => {
    const ownerUserId = c.req.query("ownerUserId") ?? "";
    if (!ownerUserId) throw badRequest("invalid_query", "ownerUserId is required");
    // Unlinked (or unknown) owners look the same: 404, no hint that the user exists.
    if (!isKeyHolderFor(c.get("userId"), ownerUserId)) throw notFound("Owner not found");
    return c.json(summaryFor(ownerUserId, c.req.query("weekStart"), c.req.query("tz")));
  });

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
      const alreadyLinked = isKeyHolderFor(userId, invite.owner_user_id);
      if (!alreadyLinked && getTier(db, invite.owner_user_id) !== "plus") {
        const { n } = db
          .prepare("SELECT COUNT(*) AS n FROM keyholder_links WHERE owner_user_id = ?")
          .get(invite.owner_user_id) as { n: number };
        if (n >= FREE_KEYHOLDER_LIMIT) {
          throw conflict(
            "keyholder_limit",
            "This account already has a key holder. Steady Plus allows more.",
          );
        }
      }
      db.prepare("UPDATE invites SET used_by = ?, used_at = ? WHERE code = ?").run(userId, now, code);
      db.prepare(
        `INSERT INTO keyholder_links (id, owner_user_id, keyholder_user_id, created_at)
         VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING`,
      ).run(randomUUID(), invite.owner_user_id, userId, now);
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

  v1.get("/summary/weekly", requireAuth, (c) =>
    c.json(summaryFor(c.get("userId"), c.req.query("weekStart"), c.req.query("tz"))),
  );

  // ---------------- Entitlements / billing ----------------

  v1.get("/entitlements", requireAuth, (c) => c.json(getEntitlement(db, c.get("userId"))));

  v1.post("/billing/verify", requireAuth, async (c) => {
    const body = await parseBody(c, BillingVerifySchema);
    if (!purchaseVerifier) {
      throw new ApiError(503, "billing_not_configured", "Purchases cannot be verified right now");
    }
    const userId = c.get("userId");
    const tokenHash = sha256(`play:${body.purchaseToken}`);
    const holder = db.prepare("SELECT user_id FROM purchases WHERE token_hash = ?").get(tokenHash) as
      | { user_id: string }
      | undefined;
    if (holder && holder.user_id !== userId) {
      throw conflict("purchase_in_use", "This purchase belongs to another account");
    }
    let result;
    try {
      result = await purchaseVerifier.verify(body.productId, body.purchaseToken);
    } catch {
      throw new ApiError(502, "billing_unavailable", "The store could not be reached. Try again.");
    }
    if (!result.valid) throw badRequest("purchase_invalid", "The purchase could not be verified");
    const now = nowMs();
    db.exec("BEGIN IMMEDIATE");
    try {
      db.prepare(
        `INSERT INTO purchases (token_hash, user_id, product_id, verified_at, expires_at)
         VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(token_hash) DO UPDATE SET product_id = excluded.product_id,
           verified_at = excluded.verified_at, expires_at = excluded.expires_at`,
      ).run(tokenHash, userId, body.productId, now, result.expiresAt?.getTime() ?? null);
      setTier(db, userId, "plus", new Date(now));
      db.exec("COMMIT");
    } catch (err) {
      if (db.isTransaction) db.exec("ROLLBACK");
      throw err;
    }
    return c.json(getEntitlement(db, userId));
  });

  app.route("/v1", v1);
  return app;
}
