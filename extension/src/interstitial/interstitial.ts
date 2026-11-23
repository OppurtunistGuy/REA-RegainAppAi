// Entry point for interstitial.html: read local state, wire the controller to chrome APIs.

import { read } from "../storage.js";
import type { Message, UnlockGrant } from "../types.js";
import { InterstitialController } from "./controller.js";
import { parseInterstitialHref } from "./params.js";

async function main(): Promise<void> {
  const [status, plan, grants] = await Promise.all([read("status"), read("plan"), read("grants")]);
  const controller = new InterstitialController(document, {
    params: parseInterstitialHref(location.href),
    status,
    plan: plan?.value ?? null,
    grants,
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
