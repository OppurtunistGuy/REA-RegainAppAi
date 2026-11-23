// TypeScript port of app.steady.rules.RulesEngine (docs/CONTRACTS.md, Phase 2).
// Semantics shared with Android via shared/schema/test-vectors.json:
//  - start is inclusive, end is exclusive;
//  - a schedule whose end < start crosses midnight and belongs to the day it starts
//    (end == start is treated as a full 24 h block starting that day);
//  - if several schedules are active: strict beats non-strict, then the one ending later, then lowest id.

import type { BlockState, Plan, Schedule, UnlockGrant, UpcomingBlock } from "./types.js";
import { addDays, atLocal, isoDayOfWeek, localDateOf, parseHHmm, type LocalDate } from "./tz.js";

interface Occurrence {
  schedule: Schedule;
  startsAt: number;
  endsAt: number;
}

function isStrict(s: Schedule): boolean {
  return s.strict ?? true;
}

/** The occurrence of `s` that starts on local `date`, if `s` runs that day. */
function occurrenceOn(s: Schedule, date: LocalDate, zone: string): Occurrence | null {
  if (!s.days.includes(isoDayOfWeek(date))) return null;
  const start = parseHHmm(s.start);
  const end = parseHHmm(s.end);
  const startsAt = atLocal(date, start, zone);
  const endsAt = end > start ? atLocal(date, end, zone) : atLocal(addDays(date, 1), end, zone);
  return { schedule: s, startsAt, endsAt };
}

/** Strict first, then later end, then lowest id. Negative = a wins. */
function compareActive(a: Occurrence, b: Occurrence): number {
  const sa = isStrict(a.schedule);
  const sb = isStrict(b.schedule);
  if (sa !== sb) return sa ? -1 : 1;
  if (a.endsAt !== b.endsAt) return b.endsAt - a.endsAt;
  return a.schedule.id < b.schedule.id ? -1 : a.schedule.id > b.schedule.id ? 1 : 0;
}

export class RulesEngine {
  constructor(private readonly zone: string) {}

  state(plan: Plan, now: number): BlockState {
    const today = localDateOf(now, this.zone);
    const candidates: Occurrence[] = [];
    for (const s of plan.schedules) {
      // An occurrence lasts at most 24 h, so only blocks starting yesterday or today can contain `now`.
      for (const d of [addDays(today, -1), today]) {
        const o = occurrenceOn(s, d, this.zone);
        if (o && o.startsAt <= now && now < o.endsAt) candidates.push(o);
      }
    }
    if (candidates.length === 0) return { kind: "idle" };
    candidates.sort(compareActive);
    const win = candidates[0]!;
    return {
      kind: "active",
      scheduleId: win.schedule.id,
      label: win.schedule.label,
      strict: isStrict(win.schedule),
      startedAt: win.startsAt,
      endsAt: win.endsAt,
      blocked: [...plan.distractions],
    };
  }

  /** Next block start strictly after `now` (ties: lowest id). Looks up to 8 days ahead. */
  nextBlock(plan: Plan, now: number): UpcomingBlock | null {
    const today = localDateOf(now, this.zone);
    let best: Occurrence | null = null;
    for (let i = 0; i <= 8; i++) {
      const d = addDays(today, i);
      for (const s of plan.schedules) {
        const o = occurrenceOn(s, d, this.zone);
        if (!o || o.startsAt <= now) continue;
        if (
          !best ||
          o.startsAt < best.startsAt ||
          (o.startsAt === best.startsAt && o.schedule.id < best.schedule.id)
        ) {
          best = o;
        }
      }
      if (best) break; // later days can only start later
    }
    return best ? toUpcoming(best) : null;
  }

  isBlocked(plan: Plan, grants: UnlockGrant[], surfaceId: string, now: number): boolean {
    const st = this.state(plan, now);
    if (st.kind !== "active") return false;
    if (!st.blocked.some((s) => s.id === surfaceId)) return false;
    return !hasGrant(grants, surfaceId, now);
  }

  blocksOn(plan: Plan, date: LocalDate): UpcomingBlock[] {
    const out: Occurrence[] = [];
    for (const s of plan.schedules) {
      const o = occurrenceOn(s, date, this.zone);
      if (o) out.push(o);
    }
    out.sort((a, b) => a.startsAt - b.startsAt || (a.schedule.id < b.schedule.id ? -1 : 1));
    return out.map(toUpcoming);
  }
}

function toUpcoming(o: Occurrence): UpcomingBlock {
  return { scheduleId: o.schedule.id, label: o.schedule.label, startsAt: o.startsAt, endsAt: o.endsAt };
}

/** A grant covers `surfaceId` when it is global (null) or names that surface, and from <= now < until. */
export function hasGrant(grants: UnlockGrant[], surfaceId: string, now: number): boolean {
  return grants.some(
    (g) => (g.surfaceId === null || g.surfaceId === surfaceId) && g.from <= now && now < g.until,
  );
}
