// Service worker. Pulls the plan + detectors, computes block state every minute, publishes it to
// chrome.storage.local for the content scripts / popup, and keeps declarativeNetRequest rules in sync.

import { ApiError, fetchDetectors, fetchPlan } from "./api.js";
import { buildDnrRules, computeStatus, isPlan, nextTransition, originsFor, pruneGrants } from "./policy.js";
import { read, write } from "./storage.js";
import type { Message, PublishedStatus } from "./types.js";
import { systemZone } from "./tz.js";

const SYNC_ALARM = "steady-sync";
const TICK_ALARM = "steady-tick";
const TRANSITION_ALARM = "steady-transition";

async function ensureAlarms(): Promise<void> {
  if (!(await chrome.alarms.get(SYNC_ALARM))) chrome.alarms.create(SYNC_ALARM, { periodInMinutes: 15 });
  if (!(await chrome.alarms.get(TICK_ALARM))) chrome.alarms.create(TICK_ALARM, { periodInMinutes: 1 });
}

async function sync(): Promise<void> {
  const settings = await read("settings");
  if (!settings.token) {
    await write("lastSyncError", "Not signed in");
    return;
  }
  try {
    const plan = await fetchPlan(settings.apiBase, settings.token);
    if (plan === null) {
      await write("plan", null); // server has no plan for this account
    } else if (isPlan(plan)) {
      await write("plan", { value: plan, fetchedAt: Date.now() });
    } else {
      throw new Error("Server sent a plan this version cannot read");
    }
    try {
      const det = await fetchDetectors(settings.apiBase, settings.token);
      if (det !== null) await write("detectors", { value: det, fetchedAt: Date.now() });
    } catch {
      // Detectors are optional for the web; keep the cached copy.
    }
    await write("lastSyncAt", Date.now());
    await write("lastSyncError", null);
  } catch (e) {
    // Offline or server error: keep enforcing the last cached plan.
    const msg =
      e instanceof ApiError && e.status === 401
        ? "Signed out on the server. Sign in again in Options."
        : e instanceof Error
          ? e.message
          : String(e);
    await write("lastSyncError", msg);
  }
}

async function canRedirectMap(domains: string[]): Promise<Map<string, boolean>> {
  const out = new Map<string, boolean>();
  for (const d of domains) {
    try {
      out.set(d, await chrome.permissions.contains({ origins: originsFor(d) }));
    } catch {
      out.set(d, false);
    }
  }
  return out;
}

async function applyRules(status: PublishedStatus): Promise<void> {
  const perms = await canRedirectMap(status.blockedDomains);
  const addRules = buildDnrRules({
    extensionId: chrome.runtime.id,
    blockedDomains: status.blockedDomains,
    blockedSurfaces: status.blockedSurfaces,
    canRedirect: (d) => perms.get(d) ?? false,
  });
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  const same =
    existing.length === addRules.length &&
    JSON.stringify([...existing].sort((a, b) => a.id - b.id)) === JSON.stringify(addRules);
  if (same) return;
  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: existing.map((r) => r.id),
    addRules,
  });
}

async function recomputeNow(): Promise<PublishedStatus> {
  const now = Date.now();
  const [plan, settings, lastSyncAt, lastSyncError, storedGrants] = await Promise.all([
    read("plan"),
    read("settings"),
    read("lastSyncAt"),
    read("lastSyncError"),
    read("grants"),
  ]);
  const grants = pruneGrants(storedGrants, now);
  if (grants.length !== storedGrants.length) await write("grants", grants);
  const status = computeStatus(plan?.value ?? null, grants, now, systemZone(), {
    signedIn: !!settings.token,
    lastSyncAt,
    lastSyncError,
  });
  await write("status", status);
  await applyRules(status);
  const next = nextTransition(status, grants, now);
  if (next !== null) chrome.alarms.create(TRANSITION_ALARM, { when: next + 500 });
  return status;
}

// Serialize recomputes so storage writes and rule updates never interleave.
let chain: Promise<unknown> = Promise.resolve();
function recompute(): Promise<PublishedStatus> {
  const p = chain.then(recomputeNow, recomputeNow);
  chain = p.catch(() => undefined);
  return p;
}

async function syncAndRecompute(): Promise<PublishedStatus> {
  await sync();
  return recompute();
}

chrome.runtime.onInstalled.addListener(() => {
  void ensureAlarms().then(syncAndRecompute);
});
chrome.runtime.onStartup.addListener(() => {
  void ensureAlarms().then(syncAndRecompute);
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === SYNC_ALARM) void syncAndRecompute();
  else if (alarm.name === TICK_ALARM || alarm.name === TRANSITION_ALARM) void recompute();
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== "local") return;
  if ("settings" in changes) void syncAndRecompute();
  else if ("grants" in changes || "plan" in changes) void recompute();
});

chrome.permissions.onAdded.addListener(() => void recompute());
chrome.permissions.onRemoved.addListener(() => void recompute());

chrome.runtime.onMessage.addListener((msg: Message, sender, sendResponse) => {
  // Only our own pages may talk to the worker.
  if (sender.id !== chrome.runtime.id) return false;
  const fromExtensionPage = !!sender.url?.startsWith(chrome.runtime.getURL(""));
  if (msg.type === "grant" && fromExtensionPage) {
    void (async () => {
      const grants = await read("grants");
      await write("grants", [...grants, msg.grant]);
      sendResponse(await recompute());
    })();
    return true;
  }
  if (msg.type === "sync") {
    void syncAndRecompute().then(sendResponse);
    return true;
  }
  if (msg.type === "recompute") {
    void recompute().then(sendResponse);
    return true;
  }
  return false;
});

// The worker can be restarted at any time; make sure alarms exist and state is fresh.
void ensureAlarms().then(recompute);
