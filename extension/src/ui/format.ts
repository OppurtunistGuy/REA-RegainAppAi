// Wording helpers for popup and options. Plain, calm wording; no streaks or scores.

import type { PublishedStatus } from "../types.js";

export function formatDuration(ms: number): string {
  const mins = Math.max(0, Math.ceil(ms / 60_000));
  if (mins < 60) return `${mins} min`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m ? `${h} h ${m} min` : `${h} h`;
}

export function formatWhen(instant: number, now: number, locale?: string): string {
  const d = new Date(instant);
  const time = d.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" });
  const sameDay = new Date(now).toDateString() === d.toDateString();
  if (sameDay) return `today ${time}`;
  const tomorrow = new Date(now + 86_400_000).toDateString() === d.toDateString();
  if (tomorrow) return `tomorrow ${time}`;
  return `${d.toLocaleDateString(locale, { weekday: "long" })} ${time}`;
}

export interface StatusView {
  state: "active" | "idle" | "no-plan" | "signed-out";
  headline: string;
  timeLeft: string | null;
  next: string | null;
  sync: string;
}

export function describeStatus(status: PublishedStatus | null, hasPlan: boolean, now: number): StatusView {
  const sync = !status
    ? "Starting…"
    : status.lastSyncError
      ? `Last sync problem: ${status.lastSyncError}`
      : status.lastSyncAt
        ? `Synced ${formatDuration(now - status.lastSyncAt)} ago`
        : "Not synced yet";
  const next = status?.next
    ? `${status.next.label} · ${formatWhen(status.next.startsAt, now)}`
    : null;
  if (!status?.signedIn && !hasPlan) {
    return { state: "signed-out", headline: "Not signed in", timeLeft: null, next: null, sync };
  }
  if (!hasPlan) return { state: "no-plan", headline: "No plan yet. Set one up in the phone app.", timeLeft: null, next, sync };
  if (status?.active && status.endsAt !== null && now < status.endsAt) {
    return {
      state: "active",
      headline: `${status.label ?? "Study block"} is on`,
      timeLeft: `${formatDuration(status.endsAt - now)} left`,
      next,
      sync,
    };
  }
  return { state: "idle", headline: "No block right now", timeLeft: null, next, sync };
}
