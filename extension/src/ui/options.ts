// Options page: API base URL, OTP sign-in, current state + next block, site permissions.

import { otpStart, otpVerify } from "../api.js";
import { originsFor, planDomains } from "../policy.js";
import { read, write, type Settings } from "../storage.js";
import type { Message, Plan } from "../types.js";
import { describeStatus } from "./format.js";
import { apiOrigin } from "./options-helpers.js";

function $<T extends HTMLElement = HTMLElement>(id: string): T {
  return document.getElementById(id) as T;
}

function say(id: string, text: string, kind: "ok" | "err" | "" = ""): void {
  const el = $(id);
  el.textContent = text;
  el.className = `msg ${kind}`;
}

let settings: Settings;
let plan: Plan | null = null;

async function renderState(): Promise<void> {
  const [status, cached] = await Promise.all([read("status"), read("plan")]);
  plan = cached?.value ?? null;
  const v = describeStatus(status, !!plan, Date.now());
  $("headline").textContent = v.headline;
  $("pill").textContent = v.state === "active" ? "block on" : v.state === "idle" ? "idle" : "setup";
  $("pill").className = v.state === "active" ? "pill urge" : "pill";
  $("timeLeft").hidden = !v.timeLeft;
  $("timeLeft").textContent = v.timeLeft ?? "";
  $("next").textContent = v.next ? `Next: ${v.next}` : "";
  $("sync").textContent = v.sync;

  const domains = plan ? planDomains(plan) : [];
  $("sitesBox").hidden = domains.length === 0;
  $("sites").replaceChildren(
    ...domains.map((d) => {
      const li = document.createElement("li");
      li.textContent = d;
      return li;
    }),
  );
}

function renderSignIn(): void {
  const signedIn = !!settings.token;
  $("signInForm").hidden = signedIn;
  $("signedInRow").hidden = !signedIn;
  $("who").textContent = signedIn
    ? "Signed in. Your plan syncs every 15 minutes."
    : "Use the same email or phone number as the phone app.";
}

async function save(): Promise<void> {
  const base = $<HTMLInputElement>("apiBase").value.trim().replace(/\/+$/, "");
  const origin = apiOrigin(base);
  if (!origin) {
    say("saveMsg", "That doesn't look like an http(s) URL.", "err");
    return;
  }
  // chrome.permissions.request must run inside the click, before any other await.
  const origins = [origin, ...(plan ? planDomains(plan).flatMap(originsFor) : [])];
  let granted = false;
  try {
    granted = await chrome.permissions.request({ origins });
  } catch {
    granted = false;
  }
  settings = { ...settings, apiBase: base };
  await write("settings", settings);
  say(
    "saveMsg",
    granted ? "Saved." : "Saved. Chrome access was not granted, so listed sites are blocked without the breath page.",
    granted ? "ok" : "",
  );
}

async function startOtp(): Promise<void> {
  const contact = $<HTMLInputElement>("contact").value.trim();
  if (!contact) return say("signMsg", "Enter your email or phone.", "err");
  try {
    await otpStart(settings.apiBase, contact);
    $("codeRow").hidden = false;
    $("verifyRow").hidden = false;
    $<HTMLInputElement>("code").focus();
    say("signMsg", "Code sent.", "ok");
  } catch (e) {
    say("signMsg", e instanceof Error ? e.message : "Could not reach the server.", "err");
  }
}

async function verifyOtp(): Promise<void> {
  const contact = $<HTMLInputElement>("contact").value.trim();
  const code = $<HTMLInputElement>("code").value.trim();
  try {
    const { token, userId } = await otpVerify(settings.apiBase, contact, code);
    settings = { ...settings, token, userId };
    await write("settings", settings); // background syncs on this change
    renderSignIn();
    say("signMsg", "Signed in. Fetching your plan…", "ok");
    const msg: Message = { type: "sync" };
    await chrome.runtime.sendMessage(msg).catch(() => undefined);
    await renderState();
    say("signMsg", "Signed in.", "ok");
  } catch (e) {
    say("signMsg", e instanceof Error ? e.message : "Could not reach the server.", "err");
  }
}

async function signOut(): Promise<void> {
  settings = { ...settings, token: null, userId: null };
  await write("settings", settings);
  renderSignIn();
  say("signMsg", "Signed out. The last plan stays cached so blocks keep working offline.");
}

async function main(): Promise<void> {
  settings = await read("settings");
  $<HTMLInputElement>("apiBase").value = settings.apiBase;
  renderSignIn();
  await renderState();
  $("saveBtn").addEventListener("click", () => void save());
  $("startBtn").addEventListener("click", () => void startOtp());
  $("verifyBtn").addEventListener("click", () => void verifyOtp());
  $("signOutBtn").addEventListener("click", () => void signOut());
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && (changes.status || changes.plan)) void renderState();
  });
}

void main();
