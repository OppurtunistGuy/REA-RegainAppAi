// Interstitial behaviour, kept free of chrome.* so it can be tested in jsdom.
// Flow (same as the phone's Intercept): 5-second breath, then three exits.
//   1. Close tab          - primary, available right away
//   2. 60-second swap     - placeholder card for now (flashcards live in the phone app)
//   3. Unlock for N min   - wait unlockPolicy.waitSeconds, type a reason (>= 8 non-space chars)

import { hasGrant } from "../rules.js";
import type { Plan, PublishedStatus, UnlockGrant, UnlockPolicy } from "../types.js";
import { unlockPolicyOf } from "../types.js";
import { MIN_REASON_CHARS, UnlockFlow, type UnlockStep } from "../unlock.js";
import { safeReturnUrl, surfaceName, type InterstitialParams } from "./params.js";

export const BREATH_SECONDS = 5;
export const SWAP_SECONDS = 60;

export interface InterstitialDeps {
  params: InterstitialParams;
  status: PublishedStatus | null;
  plan: Plan | null;
  grants: UnlockGrant[];
  closeTab: () => void;
  navigate: (url: string) => void;
  /** Store the grant and wait until blocking rules are updated. */
  saveGrant: (grant: UnlockGrant) => Promise<void>;
  now?: () => number;
}

export type View = "breath" | "choices" | "swap" | "unlock" | "idle" | "done";

/** Policy used by the browser: non-strict schedules never put friction on the exits. */
export function effectivePolicy(plan: Plan | null, strict: boolean): UnlockPolicy {
  const p = unlockPolicyOf(plan);
  return strict ? p : { ...p, waitSeconds: 0, requireReason: false };
}

export class InterstitialController {
  view: View = "breath";
  private timers: ReturnType<typeof setInterval>[] = [];
  private waitStartedAt = 0;
  private readonly flow: UnlockFlow;
  private readonly policy: UnlockPolicy;
  private readonly now: () => number;

  constructor(
    private readonly doc: Document,
    private readonly deps: InterstitialDeps,
  ) {
    this.now = deps.now ?? (() => Date.now());
    this.policy = effectivePolicy(deps.plan, deps.status?.strict ?? true);
    this.flow = new UnlockFlow(this.policy, !!deps.plan?.keyHolder);
  }

  private $<T extends HTMLElement = HTMLElement>(id: string): T {
    const el = this.doc.getElementById(id);
    if (!el) throw new Error(`#${id} missing from interstitial.html`);
    return el as T;
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

    if (!this.stillBlocked()) {
      this.show("idle");
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
    const kh = this.deps.plan?.keyHolder;
    const note = this.$("keyholderNote");
    note.hidden = !(kh && this.policy.notifyKeyHolder);
    note.textContent = kh
      ? `${kh.name} can't be asked from the browser yet. This unlock stays on this computer.`
      : "";
    this.$("reasonHint").textContent = this.policy.requireReason
      ? `A few words on why (at least ${MIN_REASON_CHARS} letters).`
      : "Optional: why do you need it?";
    this.renderUnlock();
    this.timers.push(setInterval(() => this.renderUnlock(), 250));
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
    btn.disabled = !(step === "READY" || step === "NEEDS_KEYHOLDER");
    btn.textContent = `Unlock ${surfaceName(this.deps.params.surface, this.deps.params.site)} for ${this.policy.approvalMinutes} min`;
    return step;
  }

  async confirmUnlock(): Promise<boolean> {
    const step = this.unlockStep();
    if (!(step === "READY" || step === "NEEDS_KEYHOLDER")) return false;
    this.clearTimers();
    const grant = this.flow.selfGrant(this.deps.params.surface, this.now());
    this.$<HTMLButtonElement>("unlockConfirm").disabled = true;
    await this.deps.saveGrant(grant);
    this.view = "done";
    this.continueToSite();
    return true;
  }

  continueToSite(): void {
    const url = safeReturnUrl(this.deps.params.url, this.deps.params.site);
    if (url) this.deps.navigate(url);
    else this.deps.closeTab();
  }
}
