// Prepara lo que no es el objeto de una prueba (empresa, emisor, certificado, catálogos) llamando a
// la API a través del proxy de la web, desde la página y con su sesión: las mismas peticiones que
// haría la aplicación, sin tokens a la vista.

import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

import { expect, type Page } from "@playwright/test";

import { CERT_PASSWORD, CERT_PATH, URLS } from "./env.ts";
import { newRuc, unique } from "./ruc.ts";

export type Company = {
  tenantId: string;
  name: string;
  taxpayerId: string;
  ruc: string;
  establishment: string;
  emissionPoint: string;
};

export type Customer = { id: string; name: string; idNumber: string; email: string };
export type Product = { id: string; sku: string; name: string; unitPrice: string };
export type Issued = { id: string; total: string };

type CallOptions = { tenantId?: string; body?: unknown; headers?: Record<string, string> };

/** Una llamada a la API como la hace la aplicación. Devuelve el estado y el cuerpo, sin lanzar. */
export async function apiCall<T = unknown>(
  page: Page,
  method: string,
  path: string,
  options: CallOptions = {},
): Promise<{ status: number; body: T }> {
  if (!page.url().startsWith(URLS.web)) {
    await page.goto("/settings/companies");
  }
  return page.evaluate(
    async ({ method, path, options }) => {
      const headers: Record<string, string> = { "X-Requested-With": "einvoice-web", ...options.headers };
      if (options.tenantId) {
        headers["X-Tenant-Id"] = options.tenantId;
      }
      if (options.body !== undefined) {
        headers["Content-Type"] = "application/json";
      }
      const response = await fetch(`/api/backend${path}`, {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      });
      const text = await response.text();
      let body: unknown = text;
      try {
        body = text === "" ? null : JSON.parse(text);
      } catch {
        // No es JSON (un archivo, por ejemplo): se devuelve el texto.
      }
      return { status: response.status, body: body as never };
    },
    { method, path, options },
  );
}

async function created<T>(page: Page, path: string, options: CallOptions, status = 201): Promise<T> {
  const response = await apiCall<T>(page, "POST", path, options);
  expect(response.status, `POST ${path}: ${JSON.stringify(response.body)}`).toBe(status);
  return response.body;
}

/** Deja esta empresa como la activa del navegador: las pantallas que se abran después trabajan con ella. */
export async function useCompany(page: Page, tenantId: string): Promise<void> {
  await page.context().addCookies([{ name: "einvoice_tenant", value: tenantId, url: URLS.web, sameSite: "Lax" }]);
}

/** Empresa nueva con emisor, establecimiento 001, punto 001 y certificado, y activa en el navegador. */
export async function seedCompany(page: Page, options: { certificate?: boolean } = {}): Promise<Company> {
  const name = `Comercial Andina ${unique()}`;
  const ruc = newRuc();
  const tenant = await created<{ id: string }>(page, "/tenants", { body: { name } });
  const tenantId = tenant.id;
  const taxpayer = await created<{ id: string }>(page, "/taxpayers", {
    tenantId,
    body: {
      ruc,
      legalName: `${name} S.A.`,
      headOfficeAddress: "Av. 10 de Agosto N21-45 y Carrión, Quito",
      defaultEnvironment: "1",
      accountingRequired: true,
      rimpeRegime: "NONE",
    },
  });
  const establishment = await created<{ id: string }>(page, `/taxpayers/${taxpayer.id}/establishments`, {
    tenantId,
    body: { code: "001", address: "Av. 10 de Agosto N21-45 y Carrión, Quito" },
  });
  await created(page, "/emission-points", { tenantId, body: { establishmentId: establishment.id, code: "001" } });
  if (options.certificate !== false) {
    await created(page, "/certificates", {
      tenantId,
      body: { taxpayerId: taxpayer.id, p12: readFileSync(CERT_PATH).toString("base64"), password: CERT_PASSWORD },
    });
  }
  await useCompany(page, tenantId);
  return { tenantId, name, taxpayerId: taxpayer.id, ruc, establishment: "001", emissionPoint: "001" };
}

/** Un cliente con cédula y un correo propio, para poder buscar el mensaje que se le envía. */
export async function seedCustomer(page: Page, company: Company, name = "Lucía Andrade Mora"): Promise<Customer> {
  const email = `cliente.${unique()}@example.test`;
  const idNumber = "1710034065";
  const customer = await created<{ id: string }>(page, "/customers", {
    tenantId: company.tenantId,
    body: { idType: "05", idNumber, name, email },
  });
  return { id: customer.id, name, idNumber, email };
}

/** Un producto con IVA del 15 % (código 4 del catálogo de la ficha). */
export async function seedProduct(page: Page, company: Company, unitPrice = "12.50"): Promise<Product> {
  const sku = `P-${unique()}`.toUpperCase();
  const name = "Taladro percutor 650 W";
  const product = await created<{ id: string }>(page, "/products", {
    tenantId: company.tenantId,
    body: { sku, name, unitPrice, ivaRateCode: "4" },
  });
  return { id: product.id, sku, name, unitPrice };
}

/** Importe con IVA del 15 % de `quantity` unidades, en centavos enteros para no perder precisión. */
export function totalWithVat(unitPrice: string, quantity: number): string {
  const [units, cents = ""] = unitPrice.split(".");
  const base = (BigInt(units ?? "0") * 100n + BigInt(cents.padEnd(2, "0").slice(0, 2))) * BigInt(quantity);
  const total = base + (base * 15n + 50n) / 100n;
  return `${total / 100n}.${String(total % 100n).padStart(2, "0")}`;
}

/** Emite una factura por la API, como lo haría el editor: una línea y un pago por el total. */
export async function seedInvoice(
  page: Page,
  company: Company,
  { customer, product, quantity = 1 }: { customer: Customer; product: Product; quantity?: number },
): Promise<Issued> {
  const total = totalWithVat(product.unitPrice, quantity);
  const issued = await created<{ id: string }>(
    page,
    "/invoices",
    {
      tenantId: company.tenantId,
      headers: { "Idempotency-Key": randomUUID() },
      body: {
        taxpayerId: company.taxpayerId,
        establishmentCode: company.establishment,
        emissionPointCode: company.emissionPoint,
        customerId: customer.id,
        lines: [{ productId: product.id, quantity: String(quantity) }],
        payments: [{ methodCode: "01", amount: total }],
      },
    },
    202,
  );
  return { id: issued.id, total };
}

export type DocumentView = {
  id: string;
  state: string;
  number: string;
  accessKey: string;
  total: string;
  authorizationNumber: string | null;
  replacesDocumentId: string | null;
  replacedByDocumentId: string | null;
};

export async function getDocument(page: Page, company: Company, id: string): Promise<DocumentView> {
  const response = await apiCall<DocumentView>(page, "GET", `/documents/${id}`, { tenantId: company.tenantId });
  expect(response.status).toBe(200);
  return response.body;
}

/** Espera a que el comprobante llegue a un estado, preguntando a la API. */
export async function waitForState(page: Page, company: Company, id: string, state: string): Promise<DocumentView> {
  await expect
    .poll(async () => (await getDocument(page, company, id)).state, { timeout: 30_000, intervals: [500] })
    .toBe(state);
  return getDocument(page, company, id);
}

export async function listDocuments(page: Page, company: Company): Promise<{ id: string; number: string; state: string }[]> {
  const response = await apiCall<{ items: { id: string; number: string; state: string }[] }>(
    page,
    "GET",
    "/documents?page=0&size=50",
    { tenantId: company.tenantId },
  );
  expect(response.status).toBe(200);
  return response.body.items;
}
