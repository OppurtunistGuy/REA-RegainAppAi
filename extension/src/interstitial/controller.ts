// Interstitial behaviour, kept free of chrome.* so it can be tested in jsdom.
// Flow (same as the phone's Intercept): 5-second breath, then three exits.
//   1. Close tab          - primary, available right away
//   2. 60-second swap     - placeholder card for now (flashcards live in the phone app)
//   3. Unlock for N min   - wait unlockPolicy.waitSeconds, type a reason (>= 8 non-space chars), then
//        - no key holder (or notifyKeyHolder off): grant locally;
//        - key holder: POST /v1/unlock-requests, poll every 10 s for up to 15 min, apply the grant
//          the key holder approves. Only the reason and the surface id are sent.

import { ApiError, type UnlockRequestStatus } from "../api.js";
import { hasGrant } from "../rules.js";
import type { PendingUnlock, Plan, PublishedStatus, UnlockGrant, UnlockPolicy } from "../types.js";
import { unlockPolicyOf } from "../types.js";
import { MIN_REASON_CHARS, UnlockFlow, type UnlockStep } from "../unlock.js";
import { safeReturnUrl, surfaceName, type InterstitialParams } from "./params.js";

export const BREATH_SECONDS = 5;
export const SWAP_SECONDS = 60;
export const POLL_MS = 10_000;
export const POLL_LIMIT_MS = 15 * 60_000;

/** Talks to the backend for key-holder unlocks. `null` in deps = not signed in. */
export interface RemoteUnlock {
  request(surfaceId: string | null, reason: string): Promise<UnlockRequestStatus>;
  poll(id: string): Promise<UnlockRequestStatus>;
}

export interface InterstitialDeps {
  params: InterstitialParams;
  status: PublishedStatus | null;
  plan: Plan | null;
  grants: UnlockGrant[];
  closeTab: () => void;
  navigate: (url: string) => void;
  /** Store the grant and wait until blocking rules are updated. */
  saveGrant: (grant: UnlockGrant) => Promise<void>;
  /** Backend access for key-holder requests; null when signed out. */
  remote?: RemoteUnlock | null;
  /** A request still waiting for an answer from an earlier visit. */
  pending?: PendingUnlock | null;
  savePending?: (p: PendingUnlock | null) => Promise<void>;
  /** Called on 401 so the user is asked to sign in again (code is e.g. "token_expired"). */
  onAuthError?: (code: string | null) => Promise<void> | void;
  now?: () => number;
}

export type View = "breath" | "choices" | "swap" | "unlock" | "waiting" | "idle" | "done";
export type WaitOutcome = "pending" | "approved" | "denied" | "timeout" | "error";

/** Policy used by the browser: non-strict schedules never put friction on the exits. */
export function effectivePolicy(plan: Plan | null, strict: boolean): UnlockPolicy {
  const p = unlockPolicyOf(plan);
  return strict ? p : { ...p, waitSeconds: 0, requireReason: false, notifyKeyHolder: false };
}

/** A key holder someone else plays. `relation: "self"` has nobody to ask. */
function askableKeyHolder(plan: Plan | null): { name: string } | null {
  const kh = plan?.keyHolder;
  return kh && kh.relation !== "self" ? kh : null;
}

export class InterstitialController {
  view: View = "breath";
  waitOutcome: WaitOutcome = "pending";
  private timers: ReturnType<typeof setInterval>[] = [];
  private waitStartedAt = 0;
  private readonly flow: UnlockFlow;
  private readonly policy: UnlockPolicy;
  private readonly now: () => number;
  private readonly keyHolder: { name: string } | null;
  private pending: PendingUnlock | null = null;
  private polling = false;
  private sending = false;

  constructor(
    private readonly doc: Document,
    private readonly deps: InterstitialDeps,
  ) {
    this.now = deps.now ?? (() => Date.now());
    this.policy = effectivePolicy(deps.plan, deps.status?.strict ?? true);
    this.keyHolder = askableKeyHolder(deps.plan);
    this.flow = new UnlockFlow(this.policy, !!this.keyHolder);
  }

  private $<T extends HTMLElement = HTMLElement>(id: string): T {
    const el = this.doc.getElementById(id);
    if (!el) throw new Error(`#${id} missing from interstitial.html`);
    return el as T;
  }

  private get asksKeyHolder(): boolean {
    return !!this.keyHolder && this.policy.notifyKeyHolder;
  }

  /** True when the surface is still blocked right now (block running and no grant). */
  stillBlocked(): boolean {
    const { status, params, grants } = this.deps;
    const now = this.now();
    if (!status?.active || (status.endsAt !== null && now >= status.endsAt)) return false;
    if (hasGrant(grants, params.surface, now)) return false;
    if (params.surface.startsWith("site:")) return status.blockedDomains.includes(params.surface.slice(5));
    return status.blockedSurfaces.includes(params.surface);
  }

  start(): void {
    const { params, status } = this.deps;
    const name = surfaceName(params.surface, params.site);
    this.$("surfaceName").textContent = name;
    this.$("blockLine").textContent = status?.label
      ? `You opened ${name} during ${status.label}.`
      : `You opened ${name}.`;
    this.$("unlockBtn").textContent = `Unlock for ${this.policy.approvalMinutes} min`;

    this.$("closeBtn").addEventListener("click", () => this.close());
    this.$("swapBtn").addEventListener("click", () => this.showSwap());
    this.$("unlockBtn").addEventListener("click", () => this.showUnlock());
    this.$("swapDone").addEventListener("click", () => this.close());
    this.$("swapBack").addEventListener("click", () => this.show("choices"));
    this.$("unlockCancel").addEventListener("click", () => this.show("choices"));
    this.$<HTMLInputElement>("reason").addEventListener("input", () => this.renderUnlock());
    this.$("unlockConfirm").addEventListener("click", () => void this.confirmUnlock());
    this.$("continueBtn").addEventListener("click", () => this.continueToSite());
    this.$("waitingClose").addEventListener("click", () => this.close());
    this.$("waitingBack").addEventListener("click", () => this.show("choices"));

    if (!this.stillBlocked()) {
      this.show("idle");
      return;
    }
    // Still waiting on the key holder from an earlier visit to this surface: keep waiting.
    const p = this.deps.pending;
    if (
      p &&
      this.asksKeyHolder &&
      this.deps.remote &&
      p.surfaceId === params.surface &&
      this.now() - p.createdAt < POLL_LIMIT_MS
    ) {
      this.pending = p;
      this.showWaiting();
      void this.pollOnce();
      return;
    }
    this.startBreath();
  }

  private clearTimers(): void {
    this.timers.forEach(clearInterval);
    this.timers = [];
  }

  show(view: View): void {
    this.clearTimers();
    this.view = view;
    const panels: Record<string, View[]> = {
      choicesPanel: ["breath", "choices"],
      swapPanel: ["swap"],
      unlockPanel: ["unlock"],
      waitingPanel: ["waiting"],
      idlePanel: ["idle"],
    };
    for (const [id, views] of Object.entries(panels)) this.$(id).hidden = !views.includes(view);
    if (view === "choices") this.setExitsEnabled(true);
  }

  private setExitsEnabled(on: boolean): void {
    this.$<HTMLButtonElement>("swapBtn").disabled = !on;
    this.$<HTMLButtonElement>("unlockBtn").disabled = !on;
  }

  private startBreath(): void {
    this.show("breath");
    const dot = this.$("breathDot");
    let n = BREATH_SECONDS;
    this.setExitsEnabled(false);
    dot.textContent = String(n);
    dot.classList.add("go");
    this.timers.push(
      setInterval(() => {
        n--;
        if (n <= 0) {
          dot.textContent = "✓";
          dot.classList.remove("go");
          this.show("choices");
        } else {
          dot.textContent = String(n);
        }
      }, 1000),
    );
  }

  close(): void {
    this.clearTimers();
    this.view = "done";
    this.deps.closeTab();
  }

  private showSwap(): void {
    this.show("swap");
    let t = SWAP_SECONDS;
    const label = this.$("swapTime");
    const bar = this.$("swapBar");
    const draw = (): void => {
      label.textContent = `${Math.floor(t / 60)}:${String(t % 60).padStart(2, "0")}`;
      bar.style.width = `${(t / SWAP_SECONDS) * 100}%`;
    };
    draw();
    this.timers.push(
      setInterval(() => {
        t = Math.max(0, t - 1);
        draw();
        if (t === 0) this.clearTimers();
      }, 1000),
    );
  }

  private showUnlock(): void {
    this.show("unlock");
    this.waitStartedAt = this.now();
    const kh = this.asksKeyHolder ? this.keyHolder : null;
    const note = this.$("keyholderNote");
    note.hidden = !kh;
    note.textContent = kh
      ? this.deps.remote
        ? `Steady will ask ${kh.name}. You get ${this.policy.approvalMinutes} min once they say yes.`
        : `${kh.name} holds your key, and this browser is signed out. Sign in again in Options to ask them.`
      : "";
    this.$("privacyLine").textContent = kh
      ? `Only your reason and what you want to unlock go to ${kh.name}. Nothing about this page is sent.`
      : "Your reason stays on this computer. Nothing about this page is sent anywhere.";
    this.$("reasonHint").textContent = this.policy.requireReason
      ? `A few words on why (at least ${MIN_REASON_CHARS} letters).`
      : "Optional: why do you need it?";
    this.setUnlockError("");
    this.renderUnlock();
    this.timers.push(setInterval(() => this.renderUnlock(), 250));
  }

  private setUnlockError(text: string): void {
    const el = this.$("unlockError");
    el.textContent = text;
    el.hidden = !text;
  }

  unlockStep(): UnlockStep {
    return this.flow.step(this.waitStartedAt, this.now(), this.$<HTMLInputElement>("reason").value);
  }

  renderUnlock(): UnlockStep {
    const step = this.unlockStep();
    const left = this.flow.secondsLeft(this.waitStartedAt, this.now());
    this.$("unlockWait").textContent =
      left > 0 ? `Wait ${left}s. The urge usually fades before this ends.` : "Wait done.";
    const btn = this.$<HTMLButtonElement>("unlockConfirm");
    const canAsk = step === "NEEDS_KEYHOLDER" && !!this.deps.remote;
    btn.disabled = this.sending || !(step === "READY" || canAsk);
    btn.textContent =
      step === "NEEDS_KEYHOLDER" && this.keyHolder
        ? `Ask ${this.keyHolder.name} for ${this.policy.approvalMinutes} min`
        : `Unlock ${surfaceName(this.deps.params.surface, this.deps.params.site)} for ${this.policy.approvalMinutes} min`;
    return step;
  }

  async confirmUnlock(): Promise<boolean> {
    const step = this.unlockStep();
    if (step === "READY") return this.grantLocally();
    if (step !== "NEEDS_KEYHOLDER" || !this.deps.remote || this.sending) return false;
    return this.askKeyHolder();
  }

  private async grantLocally(): Promise<boolean> {
    this.clearTimers();
    const grant = this.flow.selfGrant(this.deps.params.surface, this.now());
    this.$<HTMLButtonElement>("unlockConfirm").disabled = true;
    await this.deps.saveGrant(grant);
    this.view = "done";
    this.continueToSite();
    return true;
  }

  private async askKeyHolder(): Promise<boolean> {
    const remote = this.deps.remote!;
    const reason = this.$<HTMLInputElement>("reason").value.trim() || "No reason given";
    this.sending = true;
    this.$<HTMLButtonElement>("unlockConfirm").disabled = true;
    this.setUnlockError("");
    let created: UnlockRequestStatus;
    try {
      created = await remote.request(this.deps.params.surface, reason);
    } catch (e) {
      this.sending = false;
      if (e instanceof ApiError && e.code === "no_keyholder") {
        // The server has no key holder linked for this account, so there is nobody to ask.
        return this.grantLocally();
      }
      if (e instanceof ApiError && e.isAuth) {
        await this.deps.onAuthError?.(e.code);
        this.setUnlockError("Your sign-in has expired. Sign in again in Options, then try again.");
      } else {
        this.setUnlockError("Couldn't reach Steady's server. Check your connection and try again.");
      }
      this.renderUnlock();
      return false;
    }
    this.sending = false;
    this.pending = { id: created.id, surfaceId: this.deps.params.surface, createdAt: this.now() };
    await this.deps.savePending?.(this.pending);
    this.showWaiting();
    return true;
  }

  private showWaiting(): void {
    this.show("waiting");
    this.waitOutcome = "pending";
    const name = this.keyHolder?.name ?? "your key holder";
    this.$("waitingTitle").textContent = `Asked ${name}`;
    this.$("waitingText").textContent =
      `Waiting for ${name} to answer. This page checks every 10 seconds for up to 15 minutes. ` +
      `Closing the tab is always fine.`;
    this.timers.push(setInterval(() => void this.pollOnce(), POLL_MS));
  }

  /** One poll. Public for tests; normally driven by the 10-second timer. */
  async pollOnce(): Promise<WaitOutcome> {
    const p = this.pending;
    const remote = this.deps.remote;
    if (!p || !remote || this.view !== "waiting") return this.waitOutcome;
    if (this.polling) return "pending";
    if (this.now() - p.createdAt >= POLL_LIMIT_MS) return this.finishWaiting("timeout");
    this.polling = true;
    let res: UnlockRequestStatus;
    try {
      res = await remote.poll(p.id);
    } catch (e) {
      this.polling = false;
      if (e instanceof ApiError && e.isAuth) {
        await this.deps.onAuthError?.(e.code);
        return this.finishWaiting("error", "Your sign-in has expired. Sign in again in Options.");
      }
      if (e instanceof ApiError && e.status === 404) {
        return this.finishWaiting("error", "That request is gone. You can ask again.");
      }
      return "pending"; // offline for a moment; try again next tick
    }
    this.polling = false;
    if (this.view !== "waiting") return this.waitOutcome;
    if (res.status === "approved") {
      const until = res.until ? Date.parse(res.until) : NaN;
      const now = this.now();
      const grant: UnlockGrant = {
        id: p.id,
        surfaceId: p.surfaceId,
        from: now,
        until: Number.isFinite(until) ? until : now + this.policy.approvalMinutes * 60_000,
        approvedBy: this.keyHolder?.name ?? "key holder",
      };
      this.clearTimers();
      this.waitOutcome = "approved";
      this.pending = null;
      await this.deps.savePending?.(null);
      await this.deps.saveGrant(grant);
      this.view = "done";
      this.continueToSite();
      return "approved";
    }
    if (res.status === "denied") return this.finishWaiting("denied");
    if (this.now() - p.createdAt >= POLL_LIMIT_MS) return this.finishWaiting("timeout");
    return "pending";
  }

  private async finishWaiting(outcome: Exclude<WaitOutcome, "pending" | "approved">, text?: string): Promise<WaitOutcome> {
    this.clearTimers();
    this.waitOutcome = outcome;
    this.pending = null;
    await this.deps.savePending?.(null);
    const name = this.keyHolder?.name ?? "Your key holder";
    const [title, body] =
      outcome === "denied"
        ? [`${name} said not now`, "That's a no for this block. Back to it; you've got this."]
        : outcome === "timeout"
          ? ["No answer yet", `${name} didn't answer within 15 minutes. You can ask again later.`]
          : ["Couldn't check the answer", text ?? "Something went wrong."];
    this.$("waitingTitle").textContent = title;
    this.$("waitingText").textContent = body;
    return outcome;
  }

  continueToSite(): void {
    const url = safeReturnUrl(this.deps.params.url, this.deps.params.site);
    if (url) this.deps.navigate(url);
    else this.deps.closeTab();
  }
}
