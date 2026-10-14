import { CERT_PASSWORD, CERT_PATH } from "../support/env.ts";
import { expect, optionValue, test } from "../support/fixtures.ts";
import { newRuc, unique } from "../support/ruc.ts";

// El alta completa de una empresa, clic a clic: es lo primero que hace quien llega.
test("una empresa se da de alta: emisor, punto de emisión, certificado, cliente y producto", async ({ page, shot }) => {
  const suffix = unique();
  const company = `Ferretería El Tornillo ${suffix}`;
  const ruc = newRuc();
  const sections = page.getByRole("navigation", { name: "Secciones" });

  await test.step("crear la empresa", async () => {
    await page.goto("/empresas");
    await page.getByLabel("Nombre de la empresa").fill(company);
    await shot("empresas");
    await page.getByRole("button", { name: "Crear empresa" }).click();

    await expect(page).toHaveURL(/\/configuracion\/emisor/);
    await expect(page.getByRole("heading", { name: "Emisor", level: 1 })).toBeVisible();
    await expect(page.locator(".rail-company")).toContainText(company);
    await expect(page.locator(".rail-company")).toContainText("Propietario");
  });

  await test.step("registrar el emisor", async () => {
    await page.getByLabel("RUC").fill(ruc);
    await page.getByLabel("Razón social").fill(`${company} S.A.`);
    await page.getByLabel("Dirección de la matriz").fill("Av. Amazonas N34-120 y Atahualpa, Quito");
    await shot("emisor-formulario");
    await page.getByRole("button", { name: "Registrar emisor" }).click();

    await expect(page.getByText(ruc)).toBeVisible();
    await expect(page.getByText("Aún no hay establecimientos.")).toBeVisible();
  });

  await test.step("añadir establecimiento y punto de emisión", async () => {
    await page.getByLabel("Código del establecimiento").fill("001");
    await page.getByLabel("Dirección del establecimiento").fill("Av. Amazonas N34-120 y Atahualpa, Quito");
    await page.getByRole("button", { name: "Añadir establecimiento" }).click();

    const establishment = page.getByRole("region", { name: "Establecimiento 001" });
    await expect(establishment).toBeVisible();
    await establishment.getByLabel("Código del nuevo punto de emisión del establecimiento 001").fill("001");
    await establishment.getByRole("button", { name: "Añadir punto de emisión" }).click();

    const point = establishment.getByRole("row", { name: /001-001/ });
    await expect(point).toContainText("Habilitado");
    await shot("emisor-registrado");
  });

  await test.step("cargar el certificado de firma", async () => {
    await sections.getByRole("link", { name: "Certificado" }).click();
    await expect(page.getByText("Aún no hay certificado")).toBeVisible();

    await page.getByLabel("Archivo de la firma electrónica (.p12)").setInputFiles(CERT_PATH);
    await page.getByLabel("Contraseña del certificado").fill(CERT_PASSWORD);
    await page.getByRole("button", { name: "Cargar certificado" }).click();

    await expect(page.getByText("Certificado cargado. Ya se puede firmar con él.")).toBeVisible();
    const certificate = page.getByRole("row", { name: /Emisor de Pruebas/ });
    await expect(certificate).toContainText("En uso");
    await expect(page.getByText("Aún no hay certificado")).toBeHidden();
    await shot("certificado");
  });

  await test.step("registrar un cliente", async () => {
    await sections.getByRole("link", { name: "Clientes" }).click();
    await page.getByLabel("Tipo de identificación").selectOption({ label: "Cédula" });
    await page.getByLabel("Número de identificación").fill("1710034065");
    await page.getByLabel("Nombre o razón social").fill("María Fernanda Pérez");
    await page.getByLabel("Correo").fill(`maria.${suffix}@example.test`);
    await page.getByRole("button", { name: "Registrar cliente" }).click();

    await expect(page.getByText("Cliente registrado: María Fernanda Pérez.")).toBeVisible();
    await page.reload();
    await expect(page.getByRole("row", { name: /1710034065/ })).toContainText("María Fernanda Pérez");
    await shot("clientes");
  });

  await test.step("registrar un producto", async () => {
    await sections.getByRole("link", { name: "Productos" }).click();
    await page.getByLabel("Código", { exact: true }).fill("MART-01");
    await page.getByLabel("Nombre", { exact: true }).fill("Martillo de carpintero");
    await page.getByLabel("Precio unitario sin IVA").fill("12.50");
    const rate = page.getByLabel("Tarifa de IVA");
    await rate.selectOption(await optionValue(rate, /15\s*%/));
    await page.getByRole("button", { name: "Registrar producto" }).click();

    await expect(page.getByText("Producto registrado: Martillo de carpintero.")).toBeVisible();
    await page.reload();
    const product = page.getByRole("row", { name: /MART-01/ });
    await expect(product).toContainText("Martillo de carpintero");
    await expect(product).toContainText("12.50");
    await shot("productos");
  });
});
