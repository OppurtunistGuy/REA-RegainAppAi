// Content script for youtube.com: hides Shorts entry points and sends /shorts/* to the interstitial
// during active blocks. YouTube is an SPA, so it also listens for its own navigation events.

import { runContent } from "./run.js";
import { YOUTUBE } from "./selectors.js";

runContent(YOUTUBE, ["yt-navigate-start", "yt-navigate-finish", "yt-page-data-updated"]);
