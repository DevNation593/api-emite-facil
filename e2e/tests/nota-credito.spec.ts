import { expect, test } from "../support/fixtures.ts";
import { selectCatalog } from "../support/catalog.ts";
import { listDocuments, seedCompany, seedCustomer, seedProduct, type Company, type Customer, type Product } from "../support/seed.ts";

let company: Company;
let customer: Customer;
let product: Product;

test.beforeEach(async ({ page }) => {
  company = await seedCompany(page);
  customer = await seedCustomer(page, company);
  product = await seedProduct(page, company);
});

test("una nota de crédito desde una factura autorizada llega a Autorizada", async ({ page, shot }) => {
  await test.step("emitir una factura de dos unidades hasta Autorizada", async () => {
    await page.goto("/facturas/nueva");
    await selectCatalog(page, "Cliente", customer);
    await selectCatalog(page, "Producto de la línea 1", product);
    await page.getByLabel("Cantidad de la línea 1").fill("2");
    await expect(page.getByLabel("Importe pagado")).toHaveValue("28.75");
    await page.getByRole("button", { name: "Emitir factura" }).click();
    await expect(page).toHaveURL(/\/documentos\/[0-9a-f-]{36}$/);
    await expect(page.getByText("Autorizada", { exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { level: 1 })).toContainText("001-001-000000001");
  });

  const invoiceUrl = page.url();

  await test.step("el detalle de la factura ofrece la nota de crédito", async () => {
    await expect(page.getByText("Acreditado $0.00 de $28.75")).toBeVisible();
    await page.getByRole("link", { name: "Nota de crédito" }).click();
    await expect(page).toHaveURL(/\/notas\/nueva\?tipo=credito&factura=[0-9a-f-]{36}$/);
  });

  await test.step("acreditar una unidad de la línea 1 por devolución", async () => {
    await page.getByLabel(/^Acreditar la línea 1/).check();
    await page.getByLabel("Cantidad de la línea 1").fill("1");
    await page.getByLabel("Motivo").fill("Devolución");

    const totals = page.locator("table.totals");
    await expect(totals.getByRole("row", { name: /^Total/ })).toContainText("14.38");
    await shot("nota-credito-formulario");
    await page.getByRole("button", { name: "Emitir nota de crédito" }).click();
    await expect(page).toHaveURL(/\/documentos\/[0-9a-f-]{36}$/);
  });

  await test.step("el detalle de la nota llega a Autorizada y dice a qué factura modifica", async () => {
    await expect(page.getByRole("heading", { level: 1 })).toContainText(/^Nota de crédito 001-001-\d{9}/);
    await expect(page.getByText("Autorizada", { exact: true })).toBeVisible();
    await expect(page.getByText(/Modifica a:\s*Factura 001-001-000000001/)).toBeVisible();
    await shot("nota-credito-autorizada");
  });

  await test.step("en Comprobantes, el filtro de notas de crédito muestra solo esa nota", async () => {
    await page.getByRole("navigation", { name: "Secciones" }).getByRole("link", { name: "Comprobantes" }).click();
    await expect(page.getByRole("row", { name: /^Factura 001-001-000000001/ })).toBeVisible();
    await page.getByLabel("Tipo").selectOption({ label: "Notas de crédito" });

    const rows = page.locator("tbody tr");
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText("Autorizada");
    await expect(rows.first()).toContainText("N. crédito");
    await expect(page.getByRole("row", { name: /^Factura/ })).toHaveCount(0);
    await shot("comprobantes-notas-de-credito");
  });

  await test.step("la factura muestra lo acreditado", async () => {
    await page.goto(invoiceUrl);
    await expect(page.getByText("Acreditado $14.38 de $28.75")).toBeVisible();

    const documents = await listDocuments(page, company);
    expect(documents).toHaveLength(2);
  });
});
