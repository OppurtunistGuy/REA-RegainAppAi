// Popup: current state, time left, next block.

import { read } from "../storage.js";
import type { Message, PublishedStatus } from "../types.js";
import { describeStatus } from "./format.js";

function $(id: string): HTMLElement {
  return document.getElementById(id)!;
}

export function renderStatus(status: PublishedStatus | null, hasPlan: boolean, now = Date.now()): void {
  const v = describeStatus(status, hasPlan, now);
  $("headline").textContent = v.headline;
  const pill = $("pill");
  pill.textContent = v.state === "active" ? "block on" : v.state === "idle" ? "idle" : "setup";
  pill.className = v.state === "active" ? "pill urge" : "pill";
  $("timeLeft").hidden = !v.timeLeft;
  $("timeLeft").textContent = v.timeLeft ?? "";
  $("nextCard").hidden = !v.next;
  $("next").textContent = v.next ?? "";
  $("sync").textContent = v.sync;
}

export function renderAuthNotice(notice: string | null): void {
  $("authBox").hidden = !notice;
  $("authNotice").textContent = notice ?? "";
}

async function refresh(): Promise<void> {
  const [status, plan, notice] = await Promise.all([read("status"), read("plan"), read("authNotice")]);
  renderStatus(status, !!plan);
  renderAuthNotice(notice);
}

$("syncBtn").addEventListener("click", () => {
  const msg: Message = { type: "sync" };
  $("sync").textContent = "Syncing…";
  void chrome.runtime.sendMessage(msg).then(refresh);
});
$("optionsBtn").addEventListener("click", () => void chrome.runtime.openOptionsPage());
$("signInBtn").addEventListener("click", () => void chrome.runtime.openOptionsPage());

void (async () => {
  // Ask the worker for a fresh computation, then render.
  const msg: Message = { type: "recompute" };
  await refresh();
  await chrome.runtime.sendMessage(msg).catch(() => undefined);
  await refresh();
})();
