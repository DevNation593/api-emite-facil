import { expect, test } from "../support/fixtures.ts";
import { unique } from "../support/ruc.ts";
import { apiCall, seedCompany, seedCustomer, seedInvoice, seedProduct, waitForState } from "../support/seed.ts";
import { clearSriScript, scriptSri } from "../support/sri.ts";
import { sinkRequests, sinkUrl, verifySignature } from "../support/webhook.ts";

type Notification = {
  eventId: string;
  eventType: string;
  tenantId: string;
  document: { id: string; number: string; accessKey: string; state: string; authorizationNumber: string | null };
};

test("el sistema de una empresa recibe avisos firmados de lo que pasa con sus comprobantes", async ({ page, shot }) => {
  const company = await seedCompany(page);
  const customer = await seedCustomer(page, company);
  const product = await seedProduct(page, company);
  const sink = `tienda-${unique()}`;
  const url = sinkUrl(sink);
  let secret = "";

  /** Los avisos recibidos, cuando ya son `count`. */
  const received = async (count: number) => {
    await expect.poll(async () => (await sinkRequests(sink)).length, { timeout: 30_000 }).toBe(count);
    return sinkRequests(sink);
  };

  await test.step("registrar la dirección: el secreto se ve una sola vez", async () => {
    await page.goto("/integraciones");
    await page.getByLabel("Dirección de su sistema").fill(url);
    await page.getByLabel("El SRI lo autorizó").check();
    await page.getByLabel("El SRI lo devolvió o no lo autorizó").check();
    await page.getByLabel("Descripción (opcional)").fill("Tienda en línea");
    await page.getByRole("button", { name: "Registrar", exact: true }).click();

    secret = await page.getByLabel("Secreto de firma").inputValue();
    expect(secret).toMatch(/^whsec_[A-Za-z0-9+/]{43}=$/);
    const row = page.getByRole("table", { name: "Direcciones registradas" }).getByRole("row", { name: new RegExp(sink) });
    await expect(row).toContainText("Activa");
    await expect(row).toContainText("El SRI lo autorizó");
    await expect(row).toContainText("Tienda en línea");
    await shot("integraciones-secreto");

    await page.reload();
    await expect(page.getByRole("table", { name: "Direcciones registradas" }).getByRole("row", { name: new RegExp(sink) })).toBeVisible();
    await expect(page.getByLabel("Secreto de firma")).toHaveCount(0);
    await expect(page.locator("main")).not.toContainText(secret);
  });

  await test.step("una factura autorizada produce un aviso firmado, sin datos del cliente ni importes", async () => {
    const invoice = await seedInvoice(page, company, { customer, product });
    const document = await waitForState(page, company, invoice.id, "AUTHORIZED");

    const [request] = await received(1);
    expect(verifySignature(secret, request!)).toBe(true);
    expect(verifySignature("whsec_" + Buffer.alloc(32, 7).toString("base64"), request!)).toBe(false);
    const notification = JSON.parse(request!.body) as Notification;
    expect(notification.eventType).toBe("document.authorized");
    expect(notification.eventId).toBe(request!.headers["webhook-id"]);
    expect(notification.tenantId).toBe(company.tenantId);
    expect(notification.document).toMatchObject({
      id: invoice.id,
      number: document.number,
      accessKey: document.accessKey,
      state: "AUTHORIZED",
      authorizationNumber: document.authorizationNumber,
    });
    for (const secretOrPersonal of [customer.name, customer.idNumber, customer.email, invoice.total, product.name]) {
      expect(request!.body).not.toContain(secretOrPersonal);
    }
  });

  await test.step("el historial de la pantalla lo muestra entregado", async () => {
    await page.getByRole("button", { name: `Ver avisos de ${url}` }).click();
    const history = page.getByRole("table", { name: new RegExp(`Avisos enviados a`) });
    const delivery = history.getByRole("row", { name: /El SRI lo autorizó/ });
    await expect(delivery).toContainText("Entregado");
    await expect(delivery.getByRole("link", { name: /Ver comprobante/ })).toBeVisible();
    await shot("integraciones-historial");
  });

  await test.step("un rechazo también se avisa", async () => {
    await scriptSri(company.ruc, { reception: "DEVUELTA" });
    const rejected = await seedInvoice(page, company, { customer, product });
    await waitForState(page, company, rejected.id, "RETURNED");
    await clearSriScript(company.ruc);

    const requests = await received(2);
    const notification = JSON.parse(requests[1]!.body) as Notification;
    expect(notification.eventType).toBe("document.rejected");
    expect(notification.document).toMatchObject({ id: rejected.id, state: "RETURNED", authorizationNumber: null });
    expect(verifySignature(secret, requests[1]!)).toBe(true);
  });

  await test.step("al cambiar el secreto, los avisos van firmados con el nuevo y con el anterior", async () => {
    page.once("dialog", (dialog) => void dialog.accept());
    await page.getByRole("button", { name: `Cambiar el secreto de ${url}` }).click();
    const rotated = await page.getByLabel("Secreto de firma").inputValue();
    expect(rotated).toMatch(/^whsec_/);
    expect(rotated).not.toBe(secret);

    const invoice = await seedInvoice(page, company, { customer, product });
    await waitForState(page, company, invoice.id, "AUTHORIZED");
    const requests = await received(3);
    expect(requests[2]!.headers["webhook-signature"]!.split(" ")).toHaveLength(2);
    expect(verifySignature(rotated, requests[2]!)).toBe(true);
    expect(verifySignature(secret, requests[2]!)).toBe(true);
  });

  await test.step("apagada, deja de recibir avisos", async () => {
    page.once("dialog", (dialog) => void dialog.accept());
    await page.getByRole("button", { name: `Apagar ${url}` }).click();
    const row = page.getByRole("table", { name: "Direcciones registradas" }).getByRole("row", { name: new RegExp(sink) });
    await expect(row.getByRole("button", { name: `Encender ${url}` })).toBeVisible();
    await expect(row).not.toContainText("Activa");

    const invoice = await seedInvoice(page, company, { customer, product });
    await waitForState(page, company, invoice.id, "AUTHORIZED");
    // El aviso se anota en la misma transacción que el cambio de estado: si fuera a salir, ya constaría.
    const subscriptions = await apiCall<{ id: string; url: string }[]>(page, "GET", "/webhooks/subscriptions", { tenantId: company.tenantId });
    const subscription = subscriptions.body.find((candidate) => candidate.url === url)!;
    const history = await apiCall<{ total: number }>(page, "GET", `/webhooks/subscriptions/${subscription.id}/deliveries?page=0&size=20`, {
      tenantId: company.tenantId,
    });
    expect(history.body.total).toBe(3);
    expect(await sinkRequests(sink)).toHaveLength(3);
  });
});

test("no se puede registrar una dirección que no sea una URL válida", async ({ page }) => {
  await seedCompany(page);
  await page.goto("/integraciones");
  await page.getByLabel("Dirección de su sistema").fill("ftp://example.test/avisos");
  await page.getByLabel("El SRI lo autorizó").check();
  await page.getByRole("button", { name: "Registrar", exact: true }).click();

  await expect(page.getByRole("alert").filter({ hasText: /direcci[oó]n/i })).toBeVisible();
  await expect(page.getByLabel("Secreto de firma")).toHaveCount(0);
  await expect(page.getByRole("table", { name: "Direcciones registradas" })).toHaveCount(0);
});
