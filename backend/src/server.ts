import { serve } from "@hono/node-server";
import { createApp } from "./app.js";
import { openDb } from "./db.js";

const port = Number(process.env.PORT ?? 8787);
const dbPath = process.env.DATABASE_PATH ?? "steady.db";

const db = openDb(dbPath);
const app = createApp({
  db,
  detectorsPath: process.env.DETECTORS_PATH || undefined,
  corsOrigins: (process.env.CORS_ORIGINS ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean),
});

const server = serve({ fetch: app.fetch, port }, (info) => {
  console.log(`steady backend listening on http://localhost:${info.port} (db: ${dbPath})`);
});

const shutdown = () => {
  server.close();
  db.close();
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
