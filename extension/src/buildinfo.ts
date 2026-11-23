// Build-time constants, injected by scripts/build.mjs with esbuild `define`.
// Unbundled (vitest) they fall back to the development values.

declare const __STEADY_DEV__: boolean | undefined;
declare const __STEADY_API_BASE__: string | undefined;

/** True for `npm run build:dev` (allows http:// and localhost API bases). */
export const DEV_BUILD: boolean = typeof __STEADY_DEV__ !== "undefined" ? __STEADY_DEV__ : true;

/** API base the build was made for (STEADY_API_BASE, default https://api.steady.app; dev: localhost). */
export const BUILD_API_BASE: string =
  typeof __STEADY_API_BASE__ !== "undefined" ? __STEADY_API_BASE__ : "http://localhost:8787";
