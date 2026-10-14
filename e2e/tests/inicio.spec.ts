import { USERS } from "../support/env.ts";
import { expect, test } from "../support/fixtures.ts";

test.describe("sin sesión", () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test("la portada lleva a la pantalla de ingreso", async ({ page, shot }) => {
    await page.goto("/");

    await expect(page).toHaveURL(/\/ingresar/);
    await expect(page.getByRole("heading", { name: "Facturación electrónica" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Iniciar sesión" })).toBeVisible();
    await shot("ingresar");
  });
});

test("con sesión se ve el marco de la aplicación con el nombre de quien entró", async ({ page }) => {
  await page.goto("/");

  await expect(page).toHaveURL(/\/documentos/);
  await expect(page.getByRole("navigation", { name: "Secciones" })).toBeVisible();
  await expect(page.getByText(USERS.demo.name)).toBeVisible();
});
