// Guion del SRI simulado, por RUC del emisor: cada prueba trabaja con su empresa y no afecta a las demás.

import { URLS } from "./env.ts";

export type SriScript = {
  reception?: "RECIBIDA" | "DEVUELTA" | "CAIDO";
  authorization?: "AUTORIZADO" | "NO AUTORIZADO" | "EN PROCESO";
};

export type SriCalls = { reception: number; accepted: number; authorization: number };

async function control(ruc: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`${URLS.simulator}/control/ruc/${ruc}`, init);
  if (!response.ok) {
    throw new Error(`El SRI simulado respondió ${response.status}: ${await response.text()}`);
  }
  return response;
}

/** Lo que el SRI simulado responderá a los comprobantes de ese RUC hasta que se quite el guion. */
export async function scriptSri(ruc: string, script: SriScript): Promise<void> {
  await control(ruc, { method: "PUT", body: JSON.stringify(script) });
}

/** Vuelve a recibir y autorizar los comprobantes de ese RUC. */
export async function clearSriScript(ruc: string): Promise<void> {
  await control(ruc, { method: "DELETE" });
}

/** Cuántas recepciones (y cuántas aceptadas) y consultas de autorización llegaron de ese RUC. */
export async function sriCalls(ruc: string): Promise<SriCalls> {
  return ((await (await control(ruc)).json()) as { calls: SriCalls }).calls;
}
