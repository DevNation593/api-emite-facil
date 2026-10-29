import { expect, test as base, type Locator, type Page } from "@playwright/test";

import { USERS, type TestUser } from "./env.ts";

/**
 * Inicia sesión como lo hace una persona: pide `startAt`, la aplicación la manda a la pantalla de
 * ingreso y, con el correo y la contraseña, la devuelve a donde iba. Por defecto, las facturas: la
 * portada ("/") se ve sin sesión y no manda a ingresar.
 */
export async function login(page: Page, user: TestUser, startAt = "/documents/invoices"): Promise<void> {
  await page.goto(startAt);
  await signIn(page, user);
  await signedIn(page);
}

/**
 * Espera a que la aplicación esté cargada con la sesión. Tras ingresar, la web navega a donde iba el
 * usuario y puede redirigir otra vez (de "/" a "/documents/invoices"): pedir otra página antes la cortaría.
 */
export async function signedIn(page: Page): Promise<void> {
  await page.waitForURL((url) => !url.pathname.startsWith("/sign-in"));
  await expect(page.getByRole("button", { name: "Cerrar sesión" })).toBeVisible();
}

/** Rellena y envía el formulario de la pantalla de ingreso, que ya está abierta. */
export async function signIn(page: Page, user: TestUser): Promise<void> {
  await page.getByLabel("Correo").fill(user.email);
  await page.getByLabel("Contraseña").fill(user.password);
  await page.getByRole("button", { name: "Ingresar" }).click();
}

/** Crea la cuenta desde la pantalla de registro. Si el correo ya tenía una, la pantalla dice lo mismo. */
export async function register(page: Page, user: TestUser): Promise<void> {
  await page.goto("/sign-up");
  await page.getByLabel("Correo").fill(user.email);
  await page.getByLabel("Contraseña", { exact: true }).fill(user.password);
  await page.getByLabel("Repita la contraseña").fill(user.password);
  await page.getByRole("button", { name: "Crear cuenta" }).click();
  await expect(page.getByText(`Le enviamos un correo a ${user.email}`)).toBeVisible();
}

/** El valor de la opción de una lista cuyo texto cumple el patrón: los catálogos vienen de la API. */
export async function optionValue(select: Locator, text: RegExp): Promise<string> {
  const option = select.locator("option", { hasText: text });
  await expect(option).toHaveCount(1);
  return (await option.getAttribute("value")) ?? "";
}

export const test = base.extend<{ shot: (name: string) => Promise<void> }>({
  // Cada prueba entra con su propia sesión, como una persona en su navegador. Compartir una guardada
  // haría que dos pruebas presentaran el mismo token de renovación, y la API cierra la sesión cuando
  // ve uno ya usado. Una prueba sin sesión lo dice con test.use({ storageState: … }).
  storageState: async ({ browser }, use, testInfo) => {
    const context = await browser.newContext({ baseURL: testInfo.project.use.baseURL });
    const page = await context.newPage();
    await login(page, USERS.demo);
    const state = await context.storageState();
    await context.close();
    await use(state);
  },
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
