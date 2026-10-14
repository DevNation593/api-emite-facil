import { expect, test as base, type Locator, type Page } from "@playwright/test";

import { URLS, type TestUser } from "./env.ts";

/**
 * Inicia sesión como lo hace una persona: desde la pantalla de ingreso, con el formulario del
 * proveedor de identidad. `startAt` es la página de la aplicación que se pide primero.
 */
export async function login(page: Page, user: TestUser, startAt = "/"): Promise<void> {
  await page.goto(startAt);
  await page.getByRole("link", { name: "Iniciar sesión" }).click();
  await page.waitForURL((url) => url.href.startsWith(URLS.issuer));
  // El formulario es de Keycloak, no nuestro: se rellena por sus identificadores.
  await page.locator("#username").fill(user.username);
  await page.locator("#password").fill(user.password);
  await page.locator("#kc-login").click();
  await page.waitForURL((url) => url.href.startsWith(URLS.web) && !url.pathname.startsWith("/auth/"));
}

/** El valor de la opción de una lista cuyo texto cumple el patrón: los catálogos vienen de la API. */
export async function optionValue(select: Locator, text: RegExp): Promise<string> {
  const option = select.locator("option", { hasText: text });
  await expect(option).toHaveCount(1);
  return (await option.getAttribute("value")) ?? "";
}

export const test = base.extend<{ shot: (name: string) => Promise<void> }>({
  // Captura de pantalla completa, adjunta al informe: sirve para mirar las pantallas una a una.
  shot: async ({ page }, use, testInfo) => {
    await use(async (name) => {
      // Lo que se mira es la pantalla ya cargada, no su estado de espera.
      await expect(page.getByText("Cargando…")).toHaveCount(0);
      const path = testInfo.outputPath(`${name}.png`);
      await page.screenshot({ path, fullPage: true });
      await testInfo.attach(name, { path, contentType: "image/png" });
    });
  },
});

export { expect };
