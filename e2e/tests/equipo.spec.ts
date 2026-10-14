import { URLS, USERS } from "../support/env.ts";
import { expect, test } from "../support/fixtures.ts";
import { apiCall, seedCompany, seedCustomer, seedInvoice, seedProduct, waitForState } from "../support/seed.ts";

// Dos personas, cada una en su navegador: quien invita y quien acepta.
test("un propietario invita a un facturador, que entra con sus permisos y sale al ser retirado", async ({ page, browser, shot }) => {
  const company = await seedCompany(page);
  const customer = await seedCustomer(page, company);
  const product = await seedProduct(page, company);
  const colega = USERS.colega;
  let link = "";

  await test.step("invitar: el enlace se muestra una vez", async () => {
    await page.goto("/equipo");
    await expect(page.getByRole("table", { name: "Miembros" }).getByRole("row", { name: /demo@example\.test/ })).toContainText("Propietario");
    await page.getByLabel("Correo de la persona").fill(colega.email);
    await page.getByLabel("Rol que tendrá").selectOption({ label: "Facturador" });
    await page.getByRole("button", { name: "Invitar", exact: true }).click();

    link = await page.getByLabel("Enlace de invitación").inputValue();
    expect(link.startsWith(`${URLS.web}/invitacion`)).toBe(true);
    const pending = page.getByRole("table", { name: "Invitaciones pendientes" }).getByRole("row", { name: new RegExp(colega.email) });
    await expect(pending).toContainText("Facturador");
    await shot("equipo-invitacion");
  });

  const colegaContext = await browser.newContext({ storageState: colega.stateFile });
  const colegaPage = await colegaContext.newPage();

  await test.step("aceptar: la empresa pasa a ser la de trabajo, con el rol concedido", async () => {
    await colegaPage.goto(link);
    await expect(colegaPage.getByText(`Le han invitado a trabajar en ${company.name} como Facturador.`)).toBeVisible();
    await colegaPage.getByRole("button", { name: "Aceptar invitación" }).click();

    await expect(colegaPage).toHaveURL(/\/documentos$/);
    await expect(colegaPage.locator(".rail-company")).toContainText(company.name);
    await expect(colegaPage.locator(".rail-company")).toContainText("Facturador");
  });

  await test.step("un facturador factura, pero no gestiona el equipo ni las integraciones", async () => {
    const sections = colegaPage.getByRole("navigation", { name: "Secciones" });
    await expect(sections.getByRole("link", { name: "Nueva factura" })).toBeVisible();
    await expect(sections.getByRole("link", { name: "Equipo" })).toHaveCount(0);
    await expect(sections.getByRole("link", { name: "Integraciones" })).toHaveCount(0);

    // Escribir la dirección a mano tampoco enseña nada.
    await colegaPage.goto("/equipo");
    await expect(colegaPage.getByText("Su rol en esta empresa no permite gestionar el equipo.")).toBeVisible();
    await expect(colegaPage.getByText("demo@example.test")).toHaveCount(0);
    await colegaPage.goto("/integraciones");
    await expect(colegaPage.getByText("Su rol en esta empresa no permite gestionar las integraciones.")).toBeVisible();

    // Y la API lo impide aunque la pantalla no lo ofrezca.
    const tenantId = company.tenantId;
    const invite = await apiCall(colegaPage, "POST", "/invitations", { tenantId, body: { email: "otro@example.test", role: "AUDITOR" } });
    expect(invite.status).toBe(403);
    const members = await apiCall(colegaPage, "GET", "/members", { tenantId });
    expect(members.status).toBe(403);
    const subscribe = await apiCall(colegaPage, "POST", "/webhooks/subscriptions", {
      tenantId,
      body: { url: `${URLS.simulator}/sink/no-debe-crearse`, eventTypes: ["document.authorized"] },
    });
    expect(subscribe.status).toBe(403);
    const subscriptions = await apiCall(colegaPage, "GET", "/webhooks/subscriptions", { tenantId });
    expect(subscriptions.status).toBe(403);

    const invoice = await seedInvoice(colegaPage, company, { customer, product });
    await waitForState(colegaPage, company, invoice.id, "AUTHORIZED");
  });

  await test.step("el propietario lo ve en el equipo y lo retira", async () => {
    await page.reload();
    const members = page.getByRole("table", { name: "Miembros" });
    const member = members.getByRole("row", { name: new RegExp(colega.email) });
    await expect(member.getByLabel(`Rol de ${colega.email}`)).toHaveValue("BILLER");
    await expect(page.getByText("No hay invitaciones pendientes.")).toBeVisible();
    await shot("equipo-miembros");

    page.once("dialog", (dialog) => void dialog.accept());
    await member.getByRole("button", { name: `Retirar a ${colega.email}` }).click();
    await expect(members.getByRole("row", { name: new RegExp(colega.email) })).toHaveCount(0);
  });

  await test.step("retirado, pierde el acceso de inmediato", async () => {
    // Sigue con la aplicación abierta en esa empresa: lo siguiente que hace lo saca a elegir otra.
    await colegaPage.getByRole("navigation", { name: "Secciones" }).getByRole("link", { name: "Comprobantes" }).click();
    await expect(colegaPage).toHaveURL(/\/empresas$/);
    await expect(colegaPage.getByRole("heading", { name: "Empresas", level: 1 })).toBeVisible();
    await expect(colegaPage.locator(".rail-company")).not.toContainText(company.name);
    await expect(colegaPage.getByText(company.name)).toHaveCount(0);
    const documents = await apiCall(colegaPage, "GET", "/documents?page=0&size=10", { tenantId: company.tenantId });
    expect(documents.status).toBe(403);
  });

  await colegaContext.close();
});

test("una invitación no la acepta quien inició sesión con otro correo", async ({ page }) => {
  const company = await seedCompany(page);
  const invitation = await apiCall<{ token: string }>(page, "POST", "/invitations", {
    tenantId: company.tenantId,
    body: { email: "alguien.mas@example.test", role: "AUDITOR" },
  });
  expect(invitation.status).toBe(201);

  // Quien la abre es demo, que no es la persona invitada.
  await page.goto(`/invitacion?codigo=${encodeURIComponent(invitation.body.token)}`);
  await expect(page.getByRole("heading", { name: "Invitación", level: 1 })).toBeVisible();
  await expect(page.getByRole("button", { name: "Aceptar invitación" })).toHaveCount(0);
  await expect(page.getByText("Esta invitación es para otro correo.")).toBeVisible();
});
