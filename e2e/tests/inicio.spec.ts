import { USERS } from "../support/env.ts";
import { expect, test } from "../support/fixtures.ts";

test.describe("sin sesión", () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test("la portada lleva a la pantalla de ingreso", async ({ page, shot }) => {
    await page.goto("/");

    await expect(page).toHaveURL(/\/sign-in/);
    await expect(page.getByRole("heading", { name: "Ingresar", level: 1 })).toBeVisible();
    await expect(page.getByLabel("Correo")).toBeVisible();
    await expect(page.getByRole("link", { name: "Crear una cuenta" })).toBeVisible();
    await shot("ingresar");
  });
});

test("con sesión se ve el marco de la aplicación con el correo de quien entró", async ({ page }) => {
  await page.goto("/");

  await expect(page).toHaveURL(/\/documents/);
  await expect(page.getByRole("navigation", { name: "Secciones" })).toBeVisible();
  await expect(page.locator(".rail-user")).toContainText(USERS.demo.email);
});
