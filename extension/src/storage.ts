// Typed wrappers around chrome.storage.local. Everything the extension keeps lives here, on this device.

import { BUILD_API_BASE } from "./buildinfo.js";
import type { PendingUnlock, Plan, PublishedStatus, UnlockGrant } from "./types.js";

export interface Settings {
  apiBase: string;
  token: string | null;
  userId: string | null;
}

export interface Cached<T> {
  value: T;
  fetchedAt: number;
}

export interface StoreShape {
  settings: Settings;
  plan: Cached<Plan> | null;
  detectors: Cached<unknown> | null;
  grants: UnlockGrant[];
  status: PublishedStatus | null;
  lastSyncAt: number | null;
  lastSyncError: string | null;
  /** Set when the server rejected the token (401, e.g. `token_expired`); popup/options ask to sign in. */
  authNotice: string | null;
  /** Key-holder request waiting for an answer, so a reopened interstitial can keep polling. */
  pendingUnlock: PendingUnlock | null;
}

export const DEFAULT_API_BASE = BUILD_API_BASE;

export const DEFAULTS: StoreShape = {
  settings: { apiBase: DEFAULT_API_BASE, token: null, userId: null },
  plan: null,
  detectors: null,
  grants: [],
  status: null,
  lastSyncAt: null,
  lastSyncError: null,
  authNotice: null,
  pendingUnlock: null,
};

export async function read<K extends keyof StoreShape>(key: K): Promise<StoreShape[K]> {
  const got = await chrome.storage.local.get(key);
  return (got[key] as StoreShape[K] | undefined) ?? DEFAULTS[key];
}

export async function write<K extends keyof StoreShape>(key: K, value: StoreShape[K]): Promise<void> {
  await chrome.storage.local.set({ [key]: value });
}

export function onChange<K extends keyof StoreShape>(key: K, cb: (value: StoreShape[K]) => void): void {
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local" || !(key in changes)) return;
    cb((changes[key]?.newValue as StoreShape[K] | undefined) ?? DEFAULTS[key]);
  });
}

/** Forget the token after a 401 and leave a notice. The cached plan keeps enforcing blocks. */
export async function markSignedOut(notice: string): Promise<void> {
  const settings = await read("settings");
  await chrome.storage.local.set({
    settings: { ...settings, token: null, userId: null },
    authNotice: notice,
    pendingUnlock: null,
  });
}
