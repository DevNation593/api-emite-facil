import { readFileSync } from "node:fs";

import { expect, test } from "../support/fixtures.ts";
import { waitForMail } from "../support/mail.ts";
import { listDocuments, seedCompany, seedCustomer, seedProduct, type Company, type Customer, type Product } from "../support/seed.ts";

let company: Company;
let customer: Customer;
let product: Product;

test.beforeEach(async ({ page }) => {
  company = await seedCompany(page);
  customer = await seedCustomer(page, company);
  product = await seedProduct(page, company);
});

/** Rellena el editor: el cliente y una línea con dos unidades del producto. */
async function writeInvoice(page: import("@playwright/test").Page): Promise<void> {
  await page.goto("/facturas/nueva");
  await page.getByLabel("Cliente", { exact: true }).selectOption(customer.id);
  await page.getByLabel("Producto de la línea 1").selectOption(product.id);
  await page.getByLabel("Cantidad de la línea 1").fill("2");
}

test("una factura va del borrador a Autorizada, se descarga y llega por correo", async ({ page, shot }) => {
  await test.step("escribirla: la vista previa calcula el total", async () => {
    await writeInvoice(page);

    const totals = page.locator("table.totals");
    await expect(totals.getByRole("row", { name: /Subtotal sin impuestos/ })).toContainText("25.00");
    await expect(totals.getByRole("row", { name: /^IVA 15%/ })).toContainText("3.75");
    await expect(totals.getByRole("row", { name: /^Total/ })).toContainText("28.75");
    await expect(page.getByLabel("Importe pagado")).toHaveValue("28.75");
    await expect(page.getByRole("status").filter({ hasText: "Borrador guardado a las" })).toBeVisible();
    await shot("factura-editor");
  });

  await test.step("retomarla desde los borradores y emitirla", async () => {
    await page.getByRole("link", { name: "Ver borradores" }).click();
    await expect(page.getByRole("row", { name: new RegExp(customer.name) })).toBeVisible();
    await shot("borradores");
    await page.getByRole("link", { name: `Continuar el borrador de ${customer.name}` }).click();

    await expect(page.getByLabel("Cantidad de la línea 1")).toHaveValue("2");
    await page.getByRole("button", { name: "Emitir factura" }).click();
    await expect(page).toHaveURL(/\/documentos\/[0-9a-f-]{36}$/);
  });

  const documentId = page.url().split("/").pop()!;
  let accessKey = "";
  let number = "";

  await test.step("el detalle llega a Autorizada sin recargar", async () => {
    await expect(page.getByText("Autorizada", { exact: true })).toBeVisible();
    await expect(page.getByText("Autorizada por el SRI. Ya tiene validez tributaria.")).toBeVisible();
    number = (await page.getByRole("heading", { level: 1 }).innerText()).match(/\d{3}-\d{3}-\d{9}/)?.[0] ?? "";
    expect(number).toBe("001-001-000000001");

    const facts = page.locator("dl.facts").first();
    await expect(facts).toContainText(customer.name);
    await expect(facts).toContainText("Pruebas");
    accessKey = (await facts.innerText()).match(/\d{49}/)?.[0] ?? "";
    expect(accessKey).toHaveLength(49);
    expect(accessKey.slice(10, 23)).toBe(company.ruc);
    // El SRI simulado autoriza con la clave como número, como hace el real.
    await expect(facts.getByText(accessKey)).toHaveCount(2);
    await expect(page.locator("main")).toContainText("28.75");
    await shot("comprobante-autorizado");
  });

  await test.step("descargar el XML autorizado y el RIDE", async () => {
    const [xmlDownload] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("link", { name: "XML autorizado" }).click(),
    ]);
    expect(xmlDownload.suggestedFilename()).toContain(number);
    const xml = readFileSync(await xmlDownload.path(), "utf8");
    expect(xml).toContain("<estado>AUTORIZADO</estado>");
    expect(xml).toContain(`<numeroAutorizacion>${accessKey}</numeroAutorizacion>`);
    expect(xml).toContain(`<claveAcceso>${accessKey}</claveAcceso>`);
    expect(xml).toContain("<importeTotal>28.75</importeTotal>");

    const [rideDownload] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("link", { name: "RIDE (PDF)" }).click(),
    ]);
    expect(rideDownload.suggestedFilename()).toContain(number);
    expect(rideDownload.suggestedFilename()).toMatch(/\.pdf$/);
    expect(readFileSync(await rideDownload.path()).subarray(0, 5).toString("latin1")).toBe("%PDF-");
  });

  await test.step("el cliente recibe el correo con los dos archivos", async () => {
    const mail = await waitForMail(customer.email);
    expect(mail.from).toBe("facturas@example.test");
    expect(mail.subject).toContain(number);
    expect(mail.attachments.map((attachment) => attachment.contentType).sort()).toEqual(["application/pdf", "application/xml"]);

    await expect(page.getByRole("row", { name: new RegExp(customer.email) })).toContainText("Aceptado por el servidor de correo");
  });

  await test.step("aparece en el monitor de comprobantes", async () => {
    await page.getByRole("navigation", { name: "Secciones" }).getByRole("link", { name: "Comprobantes" }).click();
    const row = page.getByRole("row", { name: new RegExp(number) });
    await expect(row).toContainText("Autorizada");
    await expect(row).toContainText("28.75");
    await shot("comprobantes");
    expect(documentId).toHaveLength(36);
  });
});

test("un doble clic en Emitir produce una sola factura", async ({ page }) => {
  await writeInvoice(page);
  await expect(page.getByLabel("Importe pagado")).toHaveValue("28.75");

  await page.getByRole("button", { name: "Emitir factura" }).dblclick();
  await expect(page).toHaveURL(/\/documentos\/[0-9a-f-]{36}$/);
  await expect(page.getByText("Autorizada", { exact: true })).toBeVisible();

  const documents = await listDocuments(page, company);
  expect(documents.map((document) => document.number)).toEqual(["001-001-000000001"]);
});
