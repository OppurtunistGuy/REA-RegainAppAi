import { serve } from "@hono/node-server";
import { createApp } from "./app.js";
import { verifierFromEnv } from "./billing.js";
import { openDb } from "./db.js";
import { otpSenderFromEnv } from "./otp.js";

const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? "0.0.0.0";
const dbPath = process.env.DATABASE_PATH ?? "steady.db";

const db = openDb(dbPath);
const purchaseVerifier = verifierFromEnv(process.env);
const otpSender = otpSenderFromEnv(process.env);
const app = createApp({
  db,
  detectorsPath: process.env.DETECTORS_PATH || undefined,
  corsOrigins: (process.env.CORS_ORIGINS ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean),
  trustProxy: process.env.TRUST_PROXY === "1" || process.env.TRUST_PROXY === "true",
  purchaseVerifier,
  otpSender,
});

const server = serve({ fetch: app.fetch, port, hostname: host }, (info) => {
  console.log(
    `steady backend listening on http://${host}:${info.port} (db: ${dbPath}, ` +
      `otp: ${otpSender?.name ?? "not configured"}, billing: ${purchaseVerifier?.name ?? "not configured"})`,
  );
});

const shutdown = () => {
  server.close();
  db.close();
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
