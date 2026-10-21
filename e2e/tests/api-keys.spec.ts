import { randomUUID } from "node:crypto";

import { expect, test } from "../support/fixtures.ts";
import { URLS } from "../support/env.ts";
import { seedCompany, seedCustomer, seedProduct, totalWithVat, waitForState } from "../support/seed.ts";

test("un sistema externo factura con una API key y deja de poder al revocarla", async ({ page, shot }) => {
  const company = await seedCompany(page);
  const customer = await seedCustomer(page, company);
  const product = await seedProduct(page, company);
  let key = "";

  /** La misma llamada de un sistema externo: solo la key, sin sesión ni empresa. */
  const issueWithKey = () =>
    fetch(`${URLS.api}/api/v1/invoices`, {
      method: "POST",
      headers: { "X-Api-Key": key, "Idempotency-Key": randomUUID(), "Content-Type": "application/json" },
      body: JSON.stringify({
        taxpayerId: company.taxpayerId,
        establishmentCode: company.establishment,
        emissionPointCode: company.emissionPoint,
        customerId: customer.id,
        lines: [{ productId: product.id, quantity: "1" }],
        payments: [{ methodCode: "01", amount: totalWithVat(product.unitPrice, 1) }],
      }),
    });

  const keys = () => page.getByRole("table", { name: "API keys" });

  await test.step("el dueño crea la key y la ve una sola vez", async () => {
    await page.goto("/settings/integrations");
    await page.getByLabel("Nombre de la key").fill("ERP");
    await page.getByRole("button", { name: "Crear API key" }).click();

    key = await page.getByLabel("API key", { exact: true }).inputValue();
    expect(key).toMatch(/^efk_[A-Za-z0-9]{12}_[A-Za-z0-9_-]{43}$/);
    await expect(keys().getByRole("row", { name: /ERP/ })).toContainText("Nunca");
    await shot("integraciones-api-key");
  });

  await test.step("con la key, un sistema externo emite una factura que el SRI autoriza", async () => {
    const response = await issueWithKey();
    expect(response.status, await response.clone().text()).toBe(202);
    const issued = (await response.json()) as { id: string };
    await waitForState(page, company, issued.id, "AUTHORIZED");
  });

  await test.step("la pantalla muestra que la key se usó", async () => {
    await page.reload();
    const row = keys().getByRole("row", { name: /ERP/ });
    await expect(row).toBeVisible();
    await expect(row).not.toContainText("Nunca");
    await expect(page.getByLabel("API key", { exact: true })).toHaveCount(0);
  });

  await test.step("al revocarla, la misma llamada recibe 401", async () => {
    let confirmation = "";
    page.once("dialog", (dialog) => {
      confirmation = dialog.message();
      void dialog.accept();
    });
    await keys().getByRole("row", { name: /ERP/ }).getByRole("button", { name: /Revocar/ }).click();
    expect(confirmation).toContain("Los sistemas que usen esta key dejarán de poder facturar");
    // La fila dice "Revocada" cuando la API ya confirmó el DELETE: desde ahí, una sola llamada basta.
    await expect(keys().getByRole("row", { name: /ERP/ })).toContainText("Revocada");
    const response = await issueWithKey();
    expect(response.status, await response.clone().text()).toBe(401);
  });
});
