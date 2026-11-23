// Shapes mirror shared/schema/plan.schema.json and docs/CONTRACTS.md ("Shared data").
// Instants are epoch milliseconds inside the extension; ISO strings on the wire.

export type SurfaceMode = "hide_section" | "block_app";

export interface Surface {
  id: string; // "instagram.reels" | "youtube.shorts" | ... | "app:<pkg>" | "site:<domain>"
  mode: SurfaceMode;
}

export interface Schedule {
  id: string;
  label: string;
  days: number[]; // ISO day of week, 1 = Monday ... 7 = Sunday
  start: string; // "HH:mm"
  end: string; // "HH:mm"; end < start crosses midnight
  strict?: boolean; // default true
}

export interface KeyHolder {
  id: string;
  name: string;
  relation: "self" | "friend" | "parent";
}

export interface UnlockPolicy {
  waitSeconds: number;
  requireReason: boolean;
  notifyKeyHolder: boolean;
  approvalMinutes: number;
}

export interface Plan {
  version: number;
  distractions: Surface[];
  schedules: Schedule[];
  keyHolder?: KeyHolder | null;
  unlockPolicy?: Partial<UnlockPolicy>;
  siteBlocks?: string[];
}

export const DEFAULT_UNLOCK_POLICY: UnlockPolicy = {
  waitSeconds: 30,
  requireReason: true,
  notifyKeyHolder: true,
  approvalMinutes: 15,
};

export function unlockPolicyOf(plan: Plan | null | undefined): UnlockPolicy {
  return { ...DEFAULT_UNLOCK_POLICY, ...(plan?.unlockPolicy ?? {}) };
}

export type BlockState =
  | { kind: "idle" }
  | {
      kind: "active";
      scheduleId: string;
      label: string;
      strict: boolean;
      startedAt: number;
      endsAt: number;
      blocked: Surface[];
    };

export interface UpcomingBlock {
  scheduleId: string;
  label: string;
  startsAt: number;
  endsAt: number;
}

export interface UnlockGrant {
  id: string;
  surfaceId: string | null; // null = everything
  from: number;
  until: number;
  approvedBy: string | null;
}

/** Messages extension pages send to the service worker. */
export type Message =
  | { type: "grant"; grant: UnlockGrant }
  | { type: "sync" }
  | { type: "recompute" };

/** What the service worker publishes to chrome.storage.local for content scripts / popup. */
export interface PublishedStatus {
  computedAt: number;
  active: boolean;
  scheduleId: string | null;
  label: string | null;
  strict: boolean;
  endsAt: number | null;
  /** Surface ids blocked right now, after unlock grants are applied. */
  blockedSurfaces: string[];
  /** Bare domains redirected right now (siteBlocks + site:* minus grants). */
  blockedDomains: string[];
  next: UpcomingBlock | null;
  signedIn: boolean;
  lastSyncAt: number | null;
  lastSyncError: string | null;
}
