import { randomInt } from "node:crypto";

/**
 * Un RUC con la forma que acepta la API (diez dígitos y 001), distinto en cada llamada: un RUC
 * pertenece a una sola empresa y la base de la pila dura más que una ejecución.
 */
export function newRuc(): string {
  // Provincia 17 (Pichincha) y tercer dígito 9 (sociedad privada), como un RUC de empresa.
  return `179${String(randomInt(0, 10_000_000)).padStart(7, "0")}001`;
}

/** Sufijo corto para nombres que deben distinguirse entre ejecuciones (empresas, códigos, correos). */
export function unique(): string {
  return `${Date.now().toString(36)}${randomInt(0, 1296).toString(36).padStart(2, "0")}`;
}
