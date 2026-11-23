import { createApp, type AppOptions } from "../src/app.js";
import { openDb, type Db } from "../src/db.js";

export class FakeClock {
  constructor(public ms = Date.parse("2026-11-23T10:00:00Z")) {}
  now = () => new Date(this.ms);
  advance(ms: number) {
    this.ms += ms;
  }
}

export interface Harness {
  db: Db;
  clock: FakeClock;
  sent: { contact: string; code: string }[];
  app: ReturnType<typeof createApp>;
  req: (method: string, path: string, opts?: { token?: string; body?: unknown; raw?: string; headers?: Record<string, string> }) => Promise<Response>;
  login: (contact: string, name?: string) => Promise<{ token: string; userId: string }>;
}

export function harness(extra: Partial<AppOptions> = {}): Harness {
  const db = openDb(":memory:");
  const clock = new FakeClock();
  const sent: { contact: string; code: string }[] = [];
  const app = createApp({
    db,
    clock,
    otpSender: (contact, code) => {
      sent.push({ contact, code });
    },
    ...extra,
  });

  const req: Harness["req"] = (method, path, opts = {}) => {
    const headers: Record<string, string> = { ...(opts.headers ?? {}) };
    if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
    let body: string | undefined;
    if (opts.raw !== undefined) body = opts.raw;
    else if (opts.body !== undefined) body = JSON.stringify(opts.body);
    if (body !== undefined) headers["Content-Type"] = "application/json";
    return Promise.resolve(app.request(path, { method, headers, body }));
  };

  const login: Harness["login"] = async (contact, name) => {
    const s = await req("POST", "/v1/auth/otp/start", { body: { contact } });
    if (s.status !== 204) throw new Error(`otp start failed ${s.status}`);
    const v = await req("POST", "/v1/auth/otp/verify", {
      body: { contact, code: "000000", ...(name ? { name } : {}) },
    });
    if (v.status !== 200) throw new Error(`otp verify failed ${v.status}`);
    return (await v.json()) as { token: string; userId: string };
  };

  return { db, clock, sent, app, req, login };
}

export const samplePlan = (version: number) => ({
  version,
  distractions: [
    { id: "instagram.reels", mode: "hide_section" },
    { id: "app:com.pubg.imobile", mode: "block_app" },
  ],
  schedules: [
    { id: "coaching", label: "Coaching", days: [1, 2, 3, 4, 5], start: "16:00", end: "19:00", strict: true },
  ],
  keyHolder: { id: "kh1", name: "Asha", relation: "friend" },
  unlockPolicy: { waitSeconds: 30, requireReason: true, notifyKeyHolder: true, approvalMinutes: 15 },
  siteBlocks: ["instagram.com"],
});

export async function errorCode(res: Response): Promise<string> {
  const j = (await res.json()) as { error?: { code?: string; message?: string } };
  return j.error?.code ?? "(none)";
}
