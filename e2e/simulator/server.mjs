// SRI simulado para las pruebas con navegador: recibe y autoriza todo salvo que un guion por RUC diga
// otra cosa. El mismo proceso recibe los avisos (webhooks) que envía la aplicación. Solo escucha en
// 127.0.0.1 y no guarda nada en disco.
//
//   PUT    /control/ruc/{ruc}     {"reception": "RECIBIDA"|"DEVUELTA"|"CAIDO",
//                                  "authorization": "AUTORIZADO"|"NO AUTORIZADO"|"EN PROCESO"}
//   GET    /control/ruc/{ruc}     cuántas recepciones y consultas llegaron de ese RUC
//   DELETE /control/ruc/{ruc}     quita el guion
// La consulta de autorización de una clave que no se recibió responde sin autorizaciones.
//   POST   /sink/{nombre}         destino de avisos; GET /control/sink/{nombre} devuelve lo recibido

import http from "node:http";
import { fileURLToPath } from "node:url";

import { authorized, notAuthorized, processing, received, returned, unknown } from "./soap.mjs";

const RECEPTIONS = new Set(["RECIBIDA", "DEVUELTA", "CAIDO"]);
const AUTHORIZATIONS = new Set(["AUTORIZADO", "NO AUTORIZADO", "EN PROCESO"]);

const RETURNED_MESSAGE = { identifier: "35", text: "ARCHIVO NO CUMPLE ESTRUCTURA XML" };
const NOT_AUTHORIZED_MESSAGE = { identifier: "39", text: "FIRMA INVALIDA" };

// El RUC del emisor va en la clave de acceso, tras la fecha (8) y el tipo de comprobante (2).
const rucOf = (accessKey) => accessKey.slice(10, 23);

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}

function send(response, status, body, contentType) {
  response.writeHead(status, { "Content-Type": contentType });
  response.end(body);
}

const json = (response, status, value) => send(response, status, JSON.stringify(value), "application/json");
const xml = (response, body) => send(response, 200, body, "text/xml; charset=UTF-8");

export function createSimulator() {
  const scripts = new Map();
  const calls = new Map();
  const invoices = new Map();
  const sinks = new Map();

  const callsOf = (ruc) => {
    if (!calls.has(ruc)) {
      calls.set(ruc, { reception: 0, accepted: 0, authorization: 0 });
    }
    return calls.get(ruc);
  };

  async function reception(request, response) {
    const soap = (await readBody(request)).toString("utf8");
    const encoded = /<xml>([^<]*)<\/xml>/.exec(soap);
    const invoice = encoded ? Buffer.from(encoded[1], "base64").toString("utf8") : "";
    const accessKey = /<claveAcceso>([0-9]{49})<\/claveAcceso>/.exec(invoice)?.[1];
    if (!accessKey) {
      send(response, 400, "sin clave de acceso", "text/plain");
      return;
    }
    const ruc = rucOf(accessKey);
    const script = scripts.get(ruc)?.reception ?? "RECIBIDA";
    callsOf(ruc).reception += 1;
    if (script === "CAIDO") {
      request.socket.destroy();
      return;
    }
    if (script === "DEVUELTA") {
      xml(response, returned(accessKey, RETURNED_MESSAGE));
      return;
    }
    callsOf(ruc).accepted += 1;
    invoices.set(accessKey, invoice.replace(/^<\?xml[^>]*\?>/, ""));
    xml(response, received());
  }

  async function authorization(request, response) {
    const soap = (await readBody(request)).toString("utf8");
    const accessKey = /<claveAccesoComprobante>\s*([0-9]{49})\s*<\/claveAccesoComprobante>/.exec(soap)?.[1];
    if (!accessKey) {
      send(response, 400, "sin clave de acceso", "text/plain");
      return;
    }
    const ruc = rucOf(accessKey);
    const script = scripts.get(ruc)?.authorization ?? "AUTORIZADO";
    callsOf(ruc).authorization += 1;
    const invoice = invoices.get(accessKey);
    if (invoice === undefined) {
      // Como el SRI real: de un comprobante que no recibió no hay nada que consultar.
      xml(response, unknown(accessKey));
    } else if (script === "EN PROCESO") {
      xml(response, processing(accessKey));
    } else if (script === "NO AUTORIZADO") {
      xml(response, notAuthorized(accessKey, NOT_AUTHORIZED_MESSAGE, invoice));
    } else {
      xml(response, authorized(accessKey, invoice));
    }
  }

  async function control(request, response, ruc) {
    if (request.method === "GET") {
      json(response, 200, { script: scripts.get(ruc) ?? {}, calls: callsOf(ruc) });
      return;
    }
    if (request.method === "DELETE") {
      scripts.delete(ruc);
      json(response, 200, {});
      return;
    }
    if (request.method !== "PUT") {
      json(response, 405, { error: "método no admitido" });
      return;
    }
    let script;
    try {
      script = JSON.parse((await readBody(request)).toString("utf8"));
    } catch {
      json(response, 400, { error: "el guion no es JSON" });
      return;
    }
    if (script.reception !== undefined && !RECEPTIONS.has(script.reception)) {
      json(response, 400, { error: `recepción desconocida: ${script.reception}` });
      return;
    }
    if (script.authorization !== undefined && !AUTHORIZATIONS.has(script.authorization)) {
      json(response, 400, { error: `autorización desconocida: ${script.authorization}` });
      return;
    }
    scripts.set(ruc, { reception: script.reception, authorization: script.authorization });
    json(response, 200, scripts.get(ruc));
  }

  async function sink(request, response, name) {
    const body = (await readBody(request)).toString("utf8");
    if (!sinks.has(name)) {
      sinks.set(name, []);
    }
    sinks.get(name).push({ headers: request.headers, body, receivedAt: new Date().toISOString() });
    json(response, 200, {});
  }

  async function handle(request, response) {
    const path = new URL(request.url, "http://127.0.0.1").pathname;
    const ruc = /^\/control\/ruc\/([0-9]{13})$/.exec(path)?.[1];
    const sinkName = /^\/sink\/([A-Za-z0-9-]{1,64})$/.exec(path)?.[1];
    const readSink = /^\/control\/sink\/([A-Za-z0-9-]{1,64})$/.exec(path)?.[1];
    if (path === "/health") {
      json(response, 200, { status: "UP" });
    } else if (path === "/recepcion" && request.method === "POST") {
      await reception(request, response);
    } else if (path === "/autorizacion" && request.method === "POST") {
      await authorization(request, response);
    } else if (ruc) {
      await control(request, response, ruc);
    } else if (sinkName && request.method === "POST") {
      await sink(request, response, sinkName);
    } else if (readSink && request.method === "GET") {
      json(response, 200, sinks.get(readSink) ?? []);
    } else {
      json(response, 404, { error: "no existe" });
    }
  }

  return http.createServer((request, response) => {
    handle(request, response).catch((error) => {
      if (!response.headersSent) {
        json(response, 500, { error: String(error) });
      }
    });
  });
}

/** Arranca el simulador en 127.0.0.1. Con puerto 0 elige uno libre. */
export function start(port) {
  const server = createSimulator();
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.SIMULATOR_PORT ?? 8190);
  await start(port);
  console.log(`SRI simulado en http://127.0.0.1:${port}`);
}
