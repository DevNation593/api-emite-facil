import { expect, test } from "../support/fixtures.ts";
import { seedCompany, seedCustomer, seedInvoice, seedProduct, waitForState } from "../support/seed.ts";

// Un teléfono pequeño: lo que se usa para mirar una factura fuera de la oficina.
test.use({ viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true });

test("las pantallas principales caben en un teléfono sin desplazarse de lado", async ({ page, shot }) => {
  const company = await seedCompany(page);
  const customer = await seedCustomer(page, company);
  const product = await seedProduct(page, company);
  const invoice = await seedInvoice(page, company, { customer, product, quantity: 2 });
  await waitForState(page, company, invoice.id, "AUTHORIZED");

  const screens: [string, string][] = [
    ["movil-comprobantes", "/documentos"],
    ["movil-comprobante", `/documentos/${invoice.id}`],
    ["movil-nueva-factura", "/facturas/nueva"],
    ["movil-clientes", "/clientes"],
    ["movil-productos", "/productos"],
    ["movil-emisor", "/configuracion/emisor"],
    ["movil-equipo", "/equipo"],
    ["movil-integraciones", "/integraciones"],
  ];
  for (const [name, path] of screens) {
    await test.step(path, async () => {
      await page.goto(path);
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      await shot(name);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect.soft(overflow, `${path} se desplaza de lado ${overflow}px`).toBeLessThanOrEqual(0);
    });
  }
});
