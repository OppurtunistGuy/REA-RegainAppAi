// Content script for instagram.com: hides Reels entry points and sends /reels/ and /reel/* to the
// interstitial during active blocks. Instagram navigates with history.pushState, which the
// Navigation API listener and the MutationObserver URL check in guard.ts both catch.

import { runContent } from "./run.js";
import { INSTAGRAM } from "./selectors.js";

runContent(INSTAGRAM);
