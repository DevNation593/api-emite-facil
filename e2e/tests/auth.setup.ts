import { USERS } from "../support/env.ts";
import { expect, login, register, test } from "../support/fixtures.ts";

// Cada usuario de las pruebas se registra como cualquiera, desde la pantalla de registro. Las demás
// pruebas entran con él, cada una con su propia sesión (support/fixtures.ts).
test.use({ storageState: { cookies: [], origins: [] } });

for (const user of Object.values(USERS)) {
  test(`registra e inicia sesión como ${user.email}`, async ({ page }) => {
    await register(page, user);
    await login(page, user);
    await expect(page.getByRole("button", { name: "Cerrar sesión" })).toBeVisible();
  });
}
