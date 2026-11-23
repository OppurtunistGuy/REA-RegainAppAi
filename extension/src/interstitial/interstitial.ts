// Entry point for interstitial.html: read local state, wire the controller to chrome APIs.

import { authMessage, createUnlockRequest, getUnlockRequest } from "../api.js";
import { markSignedOut, read, write } from "../storage.js";
import type { Message, UnlockGrant } from "../types.js";
import { InterstitialController, type RemoteUnlock } from "./controller.js";
import { parseInterstitialHref } from "./params.js";

async function main(): Promise<void> {
  const [status, plan, grants, settings, pending] = await Promise.all([
    read("status"),
    read("plan"),
    read("grants"),
    read("settings"),
    read("pendingUnlock"),
  ]);
  const token = settings.token;
  const remote: RemoteUnlock | null = token
    ? {
        request: (surfaceId, reason) => createUnlockRequest(settings.apiBase, token, surfaceId, reason),
        poll: (id) => getUnlockRequest(settings.apiBase, token, id),
      }
    : null;
  const controller = new InterstitialController(document, {
    params: parseInterstitialHref(location.href),
    status,
    plan: plan?.value ?? null,
    grants,
    remote,
    pending,
    savePending: (p) => write("pendingUnlock", p),
    onAuthError: (code) => markSignedOut(authMessage(code)),
    closeTab: () => {
      void (async () => {
        const tab = await chrome.tabs.getCurrent();
        if (tab?.id === undefined) return window.close();
        // Closing the last tab would close the window; show a new tab page instead.
        const siblings = await chrome.tabs.query({ windowId: tab.windowId });
        if (siblings.length <= 1) await chrome.tabs.update(tab.id, { url: "chrome://newtab/" });
        else await chrome.tabs.remove(tab.id);
      })();
    },
    navigate: (url) => location.replace(url),
    saveGrant: async (grant: UnlockGrant) => {
      const msg: Message = { type: "grant", grant };
      await chrome.runtime.sendMessage(msg);
    },
  });
  controller.start();
}

void main();
