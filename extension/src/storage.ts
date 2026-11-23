// Typed wrappers around chrome.storage.local. Everything the extension keeps lives here, on this device.

import type { Plan, PublishedStatus, UnlockGrant } from "./types.js";

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
}

export const DEFAULT_API_BASE = "http://localhost:8787";

export const DEFAULTS: StoreShape = {
  settings: { apiBase: DEFAULT_API_BASE, token: null, userId: null },
  plan: null,
  detectors: null,
  grants: [],
  status: null,
  lastSyncAt: null,
  lastSyncError: null,
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
