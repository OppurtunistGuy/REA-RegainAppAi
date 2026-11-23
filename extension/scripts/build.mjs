// Bundles the extension into dist/ (load it with chrome://extensions -> "Load unpacked").
import { build } from "esbuild";
import { cp, mkdir, rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(root, "dist");
const watch = process.argv.includes("--watch");

await rm(dist, { recursive: true, force: true });
await mkdir(dist, { recursive: true });

const entries = {
  background: "src/background.ts",
  "content/youtube": "src/content/youtube.ts",
  "content/instagram": "src/content/instagram.ts",
  interstitial: "src/interstitial/interstitial.ts",
  options: "src/ui/options.ts",
  popup: "src/ui/popup.ts",
};

const options = {
  absWorkingDir: root,
  entryPoints: entries,
  outdir: dist,
  bundle: true,
  format: "iife",
  target: "chrome116",
  sourcemap: watch ? "inline" : false,
  minify: false,
  legalComments: "none",
  logLevel: "info",
};

if (watch) {
  const { context } = await import("esbuild");
  const ctx = await context(options);
  await cp(join(root, "static"), dist, { recursive: true });
  await ctx.watch();
} else {
  await build(options);
  await cp(join(root, "static"), dist, { recursive: true });
  console.log(`Built ${dist}`);
}
