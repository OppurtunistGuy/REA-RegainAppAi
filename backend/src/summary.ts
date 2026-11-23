export interface StoredSession {
  startMs: number;
  endMs: number;
  intercepts: number;
  unlocks: number;
}

export interface WeeklySummary {
  focusMinutes: number;
  blocks: number;
  intercepts: number;
  unlocks: number;
  days: string[];
}

const DAY_MS = 86_400_000;

/** Parses a strict YYYY-MM-DD calendar date; returns UTC midnight ms or null. */
export function parseDate(s: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return null;
  const ms = Date.parse(`${s}T00:00:00Z`);
  if (Number.isNaN(ms)) return null;
  if (new Date(ms).toISOString().slice(0, 10) !== s) return null; // rejects 2026-02-30
  return ms;
}

export function isValidTimeZone(tz: string): boolean {
  try {
    new Intl.DateTimeFormat("en-CA", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

export function localDate(ms: number, timeZone: string): string {
  // en-CA formats as YYYY-MM-DD.
  return new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date(ms));
}

/** UTC window that surely covers every instant whose local date falls in the week. */
export function queryWindow(weekStartMs: number): { fromMs: number; toMs: number } {
  return { fromMs: weekStartMs - DAY_MS, toMs: weekStartMs + 8 * DAY_MS };
}

/**
 * Aggregates sessions for the 7 local days starting at `weekStart`.
 * A session belongs to the local date it starts on (same convention as schedules
 * that cross midnight). focusMinutes is the rounded total duration.
 */
export function weeklySummary(
  sessions: StoredSession[],
  weekStart: string,
  timeZone = "UTC",
): WeeklySummary {
  const startMs = parseDate(weekStart);
  if (startMs === null) throw new Error(`invalid weekStart ${weekStart}`);
  const weekDays = new Set<string>();
  for (let i = 0; i < 7; i++) weekDays.add(new Date(startMs + i * DAY_MS).toISOString().slice(0, 10));

  let totalMs = 0;
  let blocks = 0;
  let intercepts = 0;
  let unlocks = 0;
  const days = new Set<string>();
  for (const s of sessions) {
    const day = localDate(s.startMs, timeZone);
    if (!weekDays.has(day)) continue;
    blocks++;
    totalMs += Math.max(0, s.endMs - s.startMs);
    intercepts += s.intercepts;
    unlocks += s.unlocks;
    days.add(day);
  }
  return {
    focusMinutes: Math.round(totalMs / 60_000),
    blocks,
    intercepts,
    unlocks,
    days: [...days].sort(),
  };
}
