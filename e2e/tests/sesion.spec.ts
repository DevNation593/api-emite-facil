import { URLS, USERS } from "../support/env.ts";
import { expect, login, signIn, test } from "../support/fixtures.ts";
import { seedCompany, seedCustomer, seedInvoice, seedProduct, useCompany, waitForState } from "../support/seed.ts";

// Cada prueba inicia su propia sesión: cerrar una no debe dejar sin sesión a las demás pruebas.
test.use({ storageState: { cookies: [], origins: [] } });

test("la sesión se renueva sola: tras caducar la ficha de acceso se emite sin volver a entrar", async ({ page }) => {
  // La ficha de acceso de esta pila dura 60 segundos (EINVOICE_AUTH_ACCESS_TOKEN_TTL); la prueba espera a que caduque.
  test.setTimeout(180_000);
  await login(page, USERS.demo);
  const company = await seedCompany(page);
  const customer = await seedCustomer(page, company);
  const product = await seedProduct(page, company);

  await page.goto("/facturas/nueva");
  await page.getByLabel("Cliente", { exact: true }).selectOption(customer.id);
  await page.getByLabel("Producto de la línea 1").selectOption(product.id);
  await page.getByLabel("Cantidad de la línea 1").fill("3");
  await expect(page.getByLabel("Importe pagado")).toHaveValue("43.13");

  const visited: string[] = [];
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) {
      visited.push(frame.url());
    }
  });
  // La única espera fija de estas pruebas: no hay otra forma de que una ficha caduque.
  await page.waitForTimeout(65_000);

  await expect(page.getByLabel("Cantidad de la línea 1")).toHaveValue("3");
  await page.getByRole("button", { name: "Emitir factura" }).click();
  await expect(page).toHaveURL(/\/documentos\/[0-9a-f-]{36}$/);
  await expect(page.getByText("Autorizada", { exact: true })).toBeVisible();
  expect(visited.filter((url) => url.includes("/ingresar"))).toEqual([]);
});

test("un enlace directo pide iniciar sesión y vuelve a esa página; cerrar sesión la cierra también en la API", async ({ page }) => {
  await login(page, USERS.demo);
  const company = await seedCompany(page);
  const customer = await seedCustomer(page, company);
  const product = await seedProduct(page, company);
  const invoice = await seedInvoice(page, company, { customer, product });
  const document = await waitForState(page, company, invoice.id, "AUTHORIZED");
  const detail = `/documentos/${invoice.id}`;

  await test.step("cerrar sesión lleva a la pantalla de ingreso", async () => {
    await page.goto(detail);
    await expect(page.getByRole("heading", { level: 1 })).toContainText(document.number);
    await page.getByRole("button", { name: "Cerrar sesión" }).click();
    await expect(page).toHaveURL(`${URLS.web}/ingresar`);
  });

  await test.step("sin sesión, ni la página ni la API enseñan el comprobante", async () => {
    const api = await page.request.get(`/api/backend/documents/${invoice.id}`);
    expect(api.status()).toBe(401);
    await page.goto(detail);
    await expect(page).toHaveURL(new RegExp(`/ingresar\\?volver=${encodeURIComponent(detail)}`));
    await expect(page.getByText(document.number)).toHaveCount(0);
  });

  await test.step("al iniciar sesión, que vuelve a pedir la contraseña, se llega al comprobante", async () => {
    await signIn(page, USERS.demo);

    await expect(page).toHaveURL(`${URLS.web}${detail}`);
    await expect(page.getByRole("heading", { level: 1 })).toContainText(document.number);
    await expect(page.getByText("Autorizada", { exact: true })).toBeVisible();
  });
});

test("un enlace a un comprobante de una empresa que no es la de trabajo no enseña el de otra", async ({ page }) => {
  await login(page, USERS.demo);
  const company = await seedCompany(page);
  const customer = await seedCustomer(page, company);
  const product = await seedProduct(page, company);
  const invoice = await seedInvoice(page, company, { customer, product });
  const other = await seedCompany(page);
  await useCompany(page, other.tenantId);

  await page.goto(`/documentos/${invoice.id}`);

  await expect(page.locator(".rail-company")).toContainText(other.name);
  await expect(page.getByText("No se encontró ese comprobante en esta empresa.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Volver a los comprobantes" })).toBeVisible();
});
