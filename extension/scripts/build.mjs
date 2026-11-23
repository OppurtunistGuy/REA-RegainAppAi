// Bundles the extension into dist/ (load it with chrome://extensions -> "Load unpacked").
import { build } from "esbuild";
import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { buildManifest, DEFAULT_PROD_API_BASE, DEV_API_BASE } from "./manifest.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(root, "dist");
const watch = process.argv.includes("--watch");
// `--dev` (npm run build:dev): localhost API allowed. Otherwise a release build: https only.
const dev = process.argv.includes("--dev") || watch;
const apiBase = (process.env.STEADY_API_BASE || (dev ? DEV_API_BASE : DEFAULT_PROD_API_BASE)).replace(/\/+$/, "");

async function writeManifest() {
  const template = JSON.parse(await readFile(join(root, "static", "manifest.json"), "utf8"));
  const manifest = buildManifest(template, { dev, apiBase });
  await writeFile(join(dist, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
  console.log(`${dev ? "Dev" : "Release"} manifest, API ${apiBase}: ${manifest.host_permissions.join(", ")}`);
}

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
  define: {
    __STEADY_DEV__: JSON.stringify(dev),
    __STEADY_API_BASE__: JSON.stringify(apiBase),
  },
};

if (watch) {
  const { context } = await import("esbuild");
  const ctx = await context(options);
  await cp(join(root, "static"), dist, { recursive: true });
  await writeManifest();
  await ctx.watch();
} else {
  await build(options);
  await cp(join(root, "static"), dist, { recursive: true });
  await writeManifest();
  console.log(`Built ${dist}`);
}
