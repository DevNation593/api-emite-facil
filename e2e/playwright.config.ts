import { defineConfig, devices } from "@playwright/test";

import { FRONTEND_DIR, PORTS, URLS, USERS, apiEnv, apiJar, webEnv } from "./support/env.ts";

const CI = Boolean(process.env.CI);

// Las pruebas comparten una API, una base y un proveedor de identidad: van de una en una. Cada archivo
// trabaja con su propia empresa, así que el orden entre archivos no importa.
export default defineConfig({
  testDir: "./tests",
  outputDir: "./test-results",
  fullyParallel: false,
  workers: 1,
  forbidOnly: CI,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  use: {
    baseURL: URLS.web,
    locale: "es-EC",
    timezoneId: "America/Guayaquil",
    actionTimeout: 10_000,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "setup", testMatch: /.*\.setup\.ts/ },
    {
      name: "chromium",
      dependencies: ["setup"],
      use: { ...devices["Desktop Chrome"], storageState: USERS.demo.stateFile },
    },
  ],
  // Lo que se despliega: el jar de la API y la compilación de producción de la web.
  webServer: [
    {
      name: "sri-simulado",
      command: "node simulator/server.mjs",
      url: `${URLS.simulator}/health`,
      env: { SIMULATOR_PORT: String(PORTS.simulator) },
      reuseExistingServer: !CI,
    },
    {
      name: "api",
      command: `java -jar "${apiJar()}"`,
      url: `${URLS.api}/actuator/health/readiness`,
      env: apiEnv(),
      timeout: 180_000,
      reuseExistingServer: !CI,
    },
    {
      name: "web",
      command: `npx next start -p ${PORTS.web}`,
      cwd: FRONTEND_DIR,
      url: `${URLS.web}/ingresar`,
      env: webEnv(),
      timeout: 60_000,
      reuseExistingServer: !CI,
    },
  ],
});
