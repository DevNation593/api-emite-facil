import assert from "node:assert/strict";
import { after, before, test } from "node:test";

import { start } from "./server.mjs";

const RUC_A = "1790012345001";
const RUC_B = "0990012345001";

// fecha (8) + tipo (2) + RUC (13) + ambiente (1) + serie (6) + secuencial (9) + código (8) + emisión (1) + dígito (1)
const accessKey = (ruc, sequence = "000000001") => `1310202601${ruc}1001001${sequence}1234567811`;

let server;
let base;

before(async () => {
  server = await start(0);
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

const receive = (key) =>
  fetch(`${base}/recepcion`, {
    method: "POST",
    headers: { "Content-Type": "text/xml; charset=UTF-8" },
    body: `<soapenv:Envelope><soapenv:Body><ec:validarComprobante><xml>${Buffer.from(
      `<?xml version="1.0" encoding="UTF-8"?><factura id="comprobante" version="1.1.0"><infoTributaria><claveAcceso>${key}</claveAcceso></infoTributaria></factura>`,
    ).toString("base64")}</xml></ec:validarComprobante></soapenv:Body></soapenv:Envelope>`,
  });

const authorize = (key) =>
  fetch(`${base}/autorizacion`, {
    method: "POST",
    headers: { "Content-Type": "text/xml; charset=UTF-8" },
    body: `<soapenv:Envelope><soapenv:Body><ec:autorizacionComprobante><claveAccesoComprobante>${key}</claveAccesoComprobante></ec:autorizacionComprobante></soapenv:Body></soapenv:Envelope>`,
  });

const script = (ruc, body) =>
  fetch(`${base}/control/ruc/${ruc}`, { method: "PUT", body: JSON.stringify(body) });

test("sin guion recibe y autoriza con la clave como número de autorización", async () => {
  const key = accessKey(RUC_B, "000000010");

  const reception = await (await receive(key)).text();
  assert.match(reception, /<estado>RECIBIDA<\/estado>/);

  const authorization = await (await authorize(key)).text();
  assert.match(authorization, /<estado>AUTORIZADO<\/estado>/);
  assert.ok(authorization.includes(`<numeroAutorizacion>${key}</numeroAutorizacion>`));
  assert.ok(authorization.includes(`<claveAccesoConsultada>${key}</claveAccesoConsultada>`));
  assert.match(authorization, /<fechaAutorizacion>\d{4}-\d{2}-\d{2}T[\d:.]+-05:00<\/fechaAutorizacion>/);
  assert.ok(authorization.includes(`<![CDATA[<factura id="comprobante" version="1.1.0">`));
});

test("un guion DEVUELTA solo afecta a su RUC", async () => {
  await script(RUC_A, { reception: "DEVUELTA" });

  const returned = await (await receive(accessKey(RUC_A))).text();
  assert.match(returned, /<estado>DEVUELTA<\/estado>/);
  assert.match(returned, /<identificador>35<\/identificador>/);
  assert.ok(returned.includes(`<claveAcceso>${accessKey(RUC_A)}</claveAcceso>`));

  const other = await (await receive(accessKey(RUC_B))).text();
  assert.match(other, /<estado>RECIBIDA<\/estado>/);

  await fetch(`${base}/control/ruc/${RUC_A}`, { method: "DELETE" });
  assert.match(await (await receive(accessKey(RUC_A))).text(), /<estado>RECIBIDA<\/estado>/);
});

test("NO AUTORIZADO y EN PROCESO en la consulta de autorización", async () => {
  const ruc = "1890012345001";
  await receive(accessKey(ruc));
  await script(ruc, { authorization: "NO AUTORIZADO" });
  const rejected = await (await authorize(accessKey(ruc))).text();
  assert.match(rejected, /<estado>NO AUTORIZADO<\/estado>/);
  assert.match(rejected, /<identificador>39<\/identificador>/);
  assert.doesNotMatch(rejected, /<numeroAutorizacion>/);

  await script(ruc, { authorization: "EN PROCESO" });
  assert.match(await (await authorize(accessKey(ruc))).text(), /<estado>EN PROCESO<\/estado>/);
});

test("CAIDO corta la conexión sin responder y el comprobante no queda recibido", async () => {
  const ruc = "0190012345001";
  await script(ruc, { reception: "CAIDO" });
  await assert.rejects(receive(accessKey(ruc)));

  const authorization = await (await authorize(accessKey(ruc))).text();
  assert.match(authorization, /<numeroComprobantes>0<\/numeroComprobantes>/);
  assert.match(authorization, /<autorizaciones\/>/);
  assert.doesNotMatch(authorization, /AUTORIZADO/);
});

test("cuenta recepciones, recepciones aceptadas y consultas por RUC", async () => {
  const ruc = "0290012345001";
  await script(ruc, { reception: "DEVUELTA" });
  await receive(accessKey(ruc));
  await fetch(`${base}/control/ruc/${ruc}`, { method: "DELETE" });
  await receive(accessKey(ruc));
  await authorize(accessKey(ruc));
  await authorize(accessKey(ruc));

  const state = await (await fetch(`${base}/control/ruc/${ruc}`)).json();
  assert.deepEqual(state.calls, { reception: 2, accepted: 1, authorization: 2 });
});

test("rechaza un guion que no conoce", async () => {
  const response = await script(RUC_A, { reception: "QUIZAS" });
  assert.equal(response.status, 400);
});

test("guarda los avisos que llegan a un destino", async () => {
  await fetch(`${base}/sink/tienda`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "webhook-id": "evt-1" },
    body: '{"eventType":"document.authorized"}',
  });

  const requests = await (await fetch(`${base}/control/sink/tienda`)).json();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].headers["webhook-id"], "evt-1");
  assert.equal(requests[0].body, '{"eventType":"document.authorized"}');
  assert.deepEqual(await (await fetch(`${base}/control/sink/otro`)).json(), []);
});
