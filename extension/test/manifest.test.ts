import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
// @ts-expect-error plain .mjs build helper without types
import { buildManifest } from "../scripts/manifest.mjs";

type Manifest = { name: string; host_permissions: string[] };
const template = JSON.parse(readFileSync(new URL("../static/manifest.json", import.meta.url), "utf8")) as Manifest;
const build = buildManifest as (t: Manifest, o: { dev: boolean; apiBase: string }) => Manifest;

describe("manifest builds", () => {
  it("the template ships no localhost or API host", () => {
    expect(template.host_permissions.join(" ")).not.toMatch(/localhost|127\.0\.0\.1|steady\.app/);
  });

  it("release: only YouTube, Instagram and the https API host", () => {
    const m = build(template, { dev: false, apiBase: "https://api.steady.app" });
    expect(m.host_permissions).toEqual(["*://*.youtube.com/*", "*://*.instagram.com/*", "https://api.steady.app/*"]);
    expect(m.name).toBe("Steady");
    const custom = build(template, { dev: false, apiBase: "https://steady.example.org:8443/" });
    expect(custom.host_permissions).toContain("https://steady.example.org/*");
  });

  it("release refuses http or localhost API bases", () => {
    expect(() => build(template, { dev: false, apiBase: "http://api.example.com" })).toThrow(/https/);
    expect(() => build(template, { dev: false, apiBase: "https://localhost:8787" })).toThrow(/localhost/);
    expect(() => build(template, { dev: false, apiBase: "not a url" })).toThrow(/not a URL/);
  });

  it("dev: adds localhost and 127.0.0.1", () => {
    const m = build(template, { dev: true, apiBase: "http://localhost:8787" });
    expect(m.host_permissions).toEqual([
      "*://*.youtube.com/*",
      "*://*.instagram.com/*",
      "http://localhost/*",
      "http://127.0.0.1/*",
    ]);
    expect(m.name).toBe("Steady (dev)");
  });
});
