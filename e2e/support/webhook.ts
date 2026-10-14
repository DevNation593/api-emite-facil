// El receptor de avisos de las pruebas vive en el simulador. Aquí se le pregunta qué llegó y se
// comprueba la firma como lo haría el sistema de un cliente (convención Standard Webhooks).

import { createHmac, timingSafeEqual } from "node:crypto";

import { URLS } from "./env.ts";

export type SinkRequest = { headers: Record<string, string>; body: string; receivedAt: string };

/** La dirección que se registra en "Integraciones" para recibir avisos con ese nombre. */
export function sinkUrl(name: string): string {
  return `${URLS.simulator}/sink/${name}`;
}

export async function sinkRequests(name: string): Promise<SinkRequest[]> {
  const response = await fetch(`${URLS.simulator}/control/sink/${name}`);
  if (!response.ok) {
    throw new Error(`El receptor de avisos respondió ${response.status}`);
  }
  return (await response.json()) as SinkRequest[];
}

/**
 * Verdadero si alguna de las firmas del aviso corresponde a ese secreto: HMAC-SHA256, con la clave
 * que sigue a `whsec_` en Base64, de `id.timestamp.cuerpo`.
 */
export function verifySignature(secret: string, request: SinkRequest): boolean {
  const key = Buffer.from(secret.replace(/^whsec_/, ""), "base64");
  const signed = `${request.headers["webhook-id"]}.${request.headers["webhook-timestamp"]}.${request.body}`;
  const expected = createHmac("sha256", key).update(signed).digest();
  return (request.headers["webhook-signature"] ?? "").split(" ").some((candidate) => {
    const [version, value] = candidate.split(",");
    const given = Buffer.from(value ?? "", "base64");
    return version === "v1" && given.length === expected.length && timingSafeEqual(given, expected);
  });
}
