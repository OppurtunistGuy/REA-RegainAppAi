// Tiny time-zone helper built on Intl.DateTimeFormat (no dependencies).
// Mirrors what java.time does for the Kotlin RulesEngine: local date/time <-> instant in a ZoneId.

export interface LocalDate {
  year: number;
  month: number; // 1-12
  day: number;
}

export interface LocalDateTime extends LocalDate {
  hour: number;
  minute: number;
  second: number;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(zone: string): Intl.DateTimeFormat {
  let f = formatters.get(zone);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", {
      timeZone: zone,
      hourCycle: "h23",
      year: "numeric",
      month: "numeric",
      day: "numeric",
      hour: "numeric",
      minute: "numeric",
      second: "numeric",
    });
    formatters.set(zone, f);
  }
  return f;
}

/** The wall-clock date and time at `instant` (epoch ms) in `zone`. */
export function toLocal(instant: number, zone: string): LocalDateTime {
  const parts = formatter(zone).formatToParts(new Date(instant));
  const get = (t: Intl.DateTimeFormatPartTypes): number => {
    const p = parts.find((x) => x.type === t);
    return p ? Number(p.value) : 0;
  };
  return {
    year: get("year"),
    month: get("month"),
    day: get("day"),
    hour: get("hour") % 24,
    minute: get("minute"),
    second: get("second"),
  };
}

/** Offset of `zone` from UTC at `instant`, in ms (local = utc + offset). */
export function offsetAt(instant: number, zone: string): number {
  const l = toLocal(instant, zone);
  const asUtc = Date.UTC(l.year, l.month - 1, l.day, l.hour, l.minute, l.second);
  const truncated = instant - (((instant % 1000) + 1000) % 1000);
  return asUtc - truncated;
}

/**
 * The instant at local `date` + `minutesOfDay` in `zone`.
 * Like ZonedDateTime.of: in an overlap the earlier offset wins; in a gap the time is pushed forward by the gap length.
 */
export function atLocal(date: LocalDate, minutesOfDay: number, zone: string): number {
  const wall = Date.UTC(date.year, date.month - 1, date.day) + minutesOfDay * 60_000;
  const DAY = 86_400_000;
  const before = offsetAt(wall - DAY, zone); // offset in force before any transition near this day
  const after = offsetAt(wall + DAY, zone); // offset in force after it
  const valid = [before, after]
    .map((o) => wall - o)
    .filter((t) => t + offsetAt(t, zone) === wall)
    .sort((a, b) => a - b);
  if (valid.length > 0) return valid[0]!; // overlap -> earlier instant
  // Gap: the wall time does not exist. Using the pre-transition offset lands just after the gap.
  return wall - before;
}

/** ISO day of week (1 = Monday ... 7 = Sunday) for a local date. */
export function isoDayOfWeek(date: LocalDate): number {
  const dow = new Date(Date.UTC(date.year, date.month - 1, date.day)).getUTCDay(); // 0 = Sunday
  return dow === 0 ? 7 : dow;
}

export function addDays(date: LocalDate, days: number): LocalDate {
  const d = new Date(Date.UTC(date.year, date.month - 1, date.day + days));
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}

export function localDateOf(instant: number, zone: string): LocalDate {
  const l = toLocal(instant, zone);
  return { year: l.year, month: l.month, day: l.day };
}

export function parseHHmm(s: string): number {
  const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(s);
  if (!m) throw new Error(`Bad time "${s}", expected HH:mm`);
  return Number(m[1]) * 60 + Number(m[2]);
}

export function systemZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
}
