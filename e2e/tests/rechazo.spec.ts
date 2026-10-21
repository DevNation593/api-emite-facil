import { expect, test } from "../support/fixtures.ts";
import { expectCatalogSelection } from "../support/catalog.ts";
import {
  getDocument,
  seedCompany,
  seedCustomer,
  seedInvoice,
  seedProduct,
  waitForState,
  type Company,
  type Customer,
  type Product,
} from "../support/seed.ts";
import { clearSriScript, scriptSri, sriCalls } from "../support/sri.ts";

let company: Company;
let customer: Customer;
let product: Product;

test.beforeEach(async ({ page }) => {
  company = await seedCompany(page);
  customer = await seedCustomer(page, company);
  product = await seedProduct(page, company);
});

test.afterEach(async () => {
  await clearSriScript(company.ruc);
});

test("una factura devuelta por el SRI se corrige emitiendo otra, y quedan enlazadas", async ({ page, shot }) => {
  await scriptSri(company.ruc, { reception: "DEVUELTA" });
  const rejected = await seedInvoice(page, company, { customer, product, quantity: 2 });
  await waitForState(page, company, rejected.id, "RETURNED");

  await test.step("el detalle dice por qué la devolvió el SRI", async () => {
    await page.goto(`/documents/${rejected.id}`);
    await expect(page.getByText("Devuelta por el SRI", { exact: true })).toBeVisible();
    await expect(page.getByText("No tiene validez tributaria.")).toBeVisible();
    const attempt = page.getByRole("row", { name: /Recepción/ });
    await expect(attempt).toContainText("35");
    await expect(attempt).toContainText("ARCHIVO NO CUMPLE ESTRUCTURA XML");
    await expect(page.getByRole("link", { name: "RIDE (PDF)" })).toHaveCount(0);
    await shot("comprobante-devuelto");
  });

  await clearSriScript(company.ruc);

  await test.step("corregir y emitir de nuevo abre una factura con los mismos datos", async () => {
    await page.getByRole("button", { name: "Corregir y emitir de nuevo" }).click();
    await expect(page).toHaveURL(/\/invoices\/new\?borrador=/);
    await expect(page.getByText("Esta factura reemplazará a un comprobante que el SRI rechazó")).toBeVisible();
    await expectCatalogSelection(page, "Cliente", customer);
    await expect(page.getByLabel("Cantidad de la línea 1")).toHaveValue("2");
    await expect(page.getByLabel("Importe pagado")).toHaveValue(rejected.total);
    await shot("factura-reemision");
    await page.getByRole("button", { name: "Emitir factura" }).click();
  });

  await test.step("la nueva queda autorizada, con otro número, y cada una enlaza a la otra", async () => {
    await expect(page).toHaveURL(/\/documents\/[0-9a-f-]{36}$/);
    await expect(page.getByText("Autorizada", { exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { level: 1 })).toContainText("001-001-000000002");
    const replacementId = page.url().split("/").pop()!;
    expect(replacementId).not.toBe(rejected.id);

    await page.getByRole("link", { name: "un comprobante que el SRI rechazó" }).click();
    await expect(page).toHaveURL(new RegExp(`/documents/${rejected.id}$`));
    await expect(page.getByRole("heading", { level: 1 })).toContainText("001-001-000000001");
    await expect(page.getByRole("button", { name: "Corregir y emitir de nuevo" })).toHaveCount(0);

    await page.getByRole("link", { name: "la factura que lo reemplazó" }).click();
    await expect(page).toHaveURL(new RegExp(`/documents/${replacementId}$`));

    expect((await getDocument(page, company, rejected.id)).replacedByDocumentId).toBe(replacementId);
    expect((await getDocument(page, company, replacementId)).replacesDocumentId).toBe(rejected.id);
  });
});

test("una factura que el SRI no autoriza muestra el motivo y se puede reemitir", async ({ page, shot }) => {
  await scriptSri(company.ruc, { authorization: "NO AUTORIZADO" });
  const rejected = await seedInvoice(page, company, { customer, product });
  await waitForState(page, company, rejected.id, "NOT_AUTHORIZED");

  await page.goto(`/documents/${rejected.id}`);
  await expect(page.getByText("No autorizada", { exact: true })).toBeVisible();
  const attempt = page.getByRole("row", { name: /Autorización/ });
  await expect(attempt).toContainText("39");
  await expect(attempt).toContainText("FIRMA INVALIDA");
  await expect(page.getByRole("row", { name: /Recepción/ })).toContainText("RECIBIDA");
  await expect(page.getByRole("button", { name: "Corregir y emitir de nuevo" })).toBeVisible();
  await shot("comprobante-no-autorizado");
});

test("si el SRI no contesta, la factura espera y se autoriza sola cuando vuelve", async ({ page, shot }) => {
  await scriptSri(company.ruc, { reception: "CAIDO" });
  const invoice = await seedInvoice(page, company, { customer, product });

  await page.goto(`/documents/${invoice.id}`);
  await expect(page.getByText("Enviando al SRI", { exact: true })).toBeVisible();
  // Insiste: no la da por perdida ni por devuelta.
  await expect.poll(async () => (await sriCalls(company.ruc)).reception, { timeout: 30_000 }).toBeGreaterThanOrEqual(2);
  await expect(page.getByText("Enviando al SRI", { exact: true })).toBeVisible();
  await expect(page.getByText("Devuelta por el SRI")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Corregir y emitir de nuevo" })).toHaveCount(0);
  await shot("comprobante-esperando-al-sri");

  await clearSriScript(company.ruc);

  // Sin recargar ni pedir nada: la pantalla sigue al comprobante.
  await expect(page.getByText("Autorizada", { exact: true })).toBeVisible({ timeout: 60_000 });
  const calls = await sriCalls(company.ruc);
  expect(calls.accepted).toBe(1);
  expect((await getDocument(page, company, invoice.id)).number).toBe("001-001-000000001");
});
