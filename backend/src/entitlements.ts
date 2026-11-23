import type { Db } from "./db.js";

export type Tier = "free" | "plus";

export const TIER_FEATURES: Record<Tier, readonly string[]> = {
  free: [],
  plus: ["unlimited_schedules", "desktop_sync", "notes_to_cards", "task_breakdown"],
};

export function getTier(db: Db, userId: string): Tier {
  const row = db.prepare("SELECT tier FROM entitlements WHERE user_id = ?").get(userId) as
    | { tier: Tier }
    | undefined;
  return row?.tier ?? "free";
}

export function getEntitlement(db: Db, userId: string): { tier: Tier; features: string[] } {
  const tier = getTier(db, userId);
  return { tier, features: [...TIER_FEATURES[tier]] };
}

/**
 * Sets a user's tier. Not exposed over HTTP: used by tests today and by the
 * billing webhook later.
 */
export function setTier(db: Db, userId: string, tier: Tier, now: Date = new Date()): void {
  db.prepare(
    `INSERT INTO entitlements (user_id, tier, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(user_id) DO UPDATE SET tier = excluded.tier, updated_at = excluded.updated_at`,
  ).run(userId, tier, now.getTime());
}
