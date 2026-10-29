import { randomUUID } from "node:crypto";

import { URLS, type TestUser } from "../support/env.ts";
import { expect, login, register, signIn, signedIn, test } from "../support/fixtures.ts";
import { linkIn, waitForMail } from "../support/mail.ts";

// Cada prueba es una persona nueva, sin sesión.
test.use({ storageState: { cookies: [], origins: [] } });

function newUser(): TestUser {
  return { email: `cuenta-${randomUUID()}@example.test`, password: "una frase larga de prueba" };
}

test("una cuenta nueva confirma su correo, cambia la contraseña y vuelve a entrar con la nueva", async ({ page, shot }) => {
  const user = newUser();

  await test.step("registrarse", async () => {
    await page.goto("/sign-up");
    await shot("registro");
    await register(page, user);
  });

  await test.step("entrar antes de confirmar el correo", async () => {
    await page.goto("/documents/invoices");
    await expect(page).toHaveURL(/\/sign-in/);
    await shot("ingresar");
    await signIn(page, user);
    await signedIn(page);
    // Sin empresa todavía: se queda en la aplicación y se le ofrece crear una.
    await expect(page.getByRole("link", { name: "elija o cree una empresa" })).toBeVisible();
    await expect(page).toHaveURL(/\/documents\/invoices$/);
    await page.goto("/account");
    await expect(page.getByText("Sin confirmar")).toBeVisible();
    await shot("cuenta-sin-confirmar");
  });

  await test.step("el enlace del correo confirma la cuenta", async () => {
    const mail = await waitForMail(user.email);
    expect(mail.subject).toBe("Confirma tu correo");
    await page.goto(linkIn(mail));
    await expect(page.getByText("Su correo quedó confirmado.")).toBeVisible();
    // El código no queda en la barra de direcciones.
    expect(page.url()).toBe(`${URLS.web}/verify-email`);
    await page.goto("/account");
    await expect(page.getByText("Confirmado", { exact: true })).toBeVisible();
  });

  await test.step("cambiar la contraseña", async () => {
    await page.getByLabel("Contraseña actual").fill(user.password);
    await page.getByLabel("Contraseña nueva", { exact: true }).fill("otra frase larga de prueba");
    await page.getByLabel("Repita la contraseña nueva").fill("otra frase larga de prueba");
    await page.getByRole("button", { name: "Cambiar contraseña" }).click();
    await expect(page.getByText("Su contraseña cambió. Las demás sesiones se cerraron.")).toBeVisible();
  });

  await test.step("salir y volver a entrar: solo vale la contraseña nueva", async () => {
    await page.getByRole("button", { name: "Cerrar sesión" }).click();
    await expect(page).toHaveURL(`${URLS.web}/sign-in`);
    await signIn(page, user);
    await expect(page.getByText("Correo o contraseña incorrectos.")).toBeVisible();
    await login(page, { ...user, password: "otra frase larga de prueba" });
    await expect(page.getByRole("button", { name: "Cerrar sesión" })).toBeVisible();
  });
});

test("quien olvidó la contraseña elige otra con el enlace del correo", async ({ page, shot }) => {
  const user = newUser();
  await register(page, user);
  await waitForMail(user.email, 1);

  await page.goto("/sign-in");
  await page.getByRole("link", { name: "Olvidé mi contraseña" }).click();
  await page.getByLabel("Correo de su cuenta").fill(user.email);
  await page.getByRole("button", { name: "Enviar enlace" }).click();
  await expect(page.getByText(`Si ${user.email} tiene una cuenta, le llegará un correo`)).toBeVisible();

  const mail = await waitForMail(user.email, 2);
  expect(mail.subject).toBe("Cambia tu contraseña");
  await page.goto(linkIn(mail));
  await shot("restablecer-contrasena");
  await page.getByLabel("Contraseña nueva").fill("la contraseña recuperada");
  await page.getByLabel("Repita la contraseña").fill("la contraseña recuperada");
  await page.getByRole("button", { name: "Guardar contraseña" }).click();
  await expect(page.getByText("Su contraseña cambió.")).toBeVisible();

  await page.getByRole("link", { name: "Ingresar" }).click();
  await login(page, { ...user, password: "la contraseña recuperada" });
  await expect(page.getByRole("button", { name: "Cerrar sesión" })).toBeVisible();
});

test("registrarse con un correo que ya tiene cuenta no lo revela en la pantalla, sí en el correo", async ({ page }) => {
  const user = newUser();
  await register(page, user);
  await waitForMail(user.email, 1);

  // La pantalla dice lo mismo que la primera vez.
  await register(page, { ...user, password: "otra frase larga cualquiera" });
  const mail = await waitForMail(user.email, 2);
  expect(mail.subject).toBe("Ya tienes una cuenta");

  // Y la contraseña no cambió.
  await login(page, user);
  await expect(page.getByRole("button", { name: "Cerrar sesión" })).toBeVisible();
});
