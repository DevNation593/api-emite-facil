import { USERS } from "../support/env.ts";
import { expect, login, test } from "../support/fixtures.ts";

// Una sesión por usuario, guardada para que las demás pruebas no repitan el inicio de sesión.
for (const user of Object.values(USERS)) {
  test(`inicia sesión como ${user.username}`, async ({ page }) => {
    await login(page, user);
    await expect(page.getByRole("button", { name: "Cerrar sesión" })).toBeVisible();
    await page.context().storageState({ path: user.stateFile });
  });
}
