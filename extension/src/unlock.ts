// Port of app.steady.rules.UnlockFlow (docs/CONTRACTS.md, Phase 2).
// A reason counts only if it has at least 8 non-space characters.

import type { UnlockGrant, UnlockPolicy } from "./types.js";

export type UnlockStep = "WAITING" | "NEEDS_REASON" | "READY" | "NEEDS_KEYHOLDER";

export const MIN_REASON_CHARS = 8;

export function reasonCounts(reason: string): boolean {
  return reason.replace(/\s+/g, "").length >= MIN_REASON_CHARS;
}

export class UnlockFlow {
  constructor(
    private readonly policy: UnlockPolicy,
    private readonly hasKeyHolder: boolean,
  ) {}

  secondsLeft(waitStartedAt: number, now: number): number {
    const elapsed = Math.max(0, now - waitStartedAt);
    return Math.max(0, Math.ceil((this.policy.waitSeconds * 1000 - elapsed) / 1000));
  }

  step(waitStartedAt: number, now: number, reason: string): UnlockStep {
    if (this.secondsLeft(waitStartedAt, now) > 0) return "WAITING";
    if (this.policy.requireReason && !reasonCounts(reason)) return "NEEDS_REASON";
    if (this.hasKeyHolder && this.policy.notifyKeyHolder) return "NEEDS_KEYHOLDER";
    return "READY";
  }

  selfGrant(
    surfaceId: string | null,
    now: number,
    minutes: number = this.policy.approvalMinutes,
    id: string = randomId(),
  ): UnlockGrant {
    return { id, surfaceId, from: now, until: now + minutes * 60_000, approvedBy: null };
  }
}

function randomId(): string {
  const c = (globalThis as { crypto?: Crypto }).crypto;
  if (c?.randomUUID) return c.randomUUID();
  return `g-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}
