import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    environment: "node",
    // node:sqlite is stable enough for us but still prints an ExperimentalWarning on Node 22.
    execArgv: ["--disable-warning=ExperimentalWarning"],
  },
});
