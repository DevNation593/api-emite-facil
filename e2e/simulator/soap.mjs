// Respuestas SOAP del SRI con la forma de los ejemplos del numeral 7.2.3 de la ficha técnica v2.34.
// Es la misma forma que usan las pruebas del backend (SriSoapSamples); si una cambia, cambia la otra.

const envelope = (body) =>
  `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>${body}</soap:Body></soap:Envelope>`;

const reception = (content) =>
  envelope(
    `<ns2:validarComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.recepcion">` +
      `<RespuestaRecepcionComprobante>${content}</RespuestaRecepcionComprobante>` +
      `</ns2:validarComprobanteResponse>`,
  );

const authorizations = (accessKey, ...items) =>
  envelope(
    `<ns2:autorizacionComprobanteResponse xmlns:ns2="http://ec.gob.sri.ws.autorizacion">` +
      `<RespuestaAutorizacionComprobante><claveAccesoConsultada>${accessKey}</claveAccesoConsultada>` +
      `<numeroComprobantes>${items.length}</numeroComprobantes>` +
      (items.length === 0 ? "<autorizaciones/>" : `<autorizaciones>${items.join("")}</autorizaciones>`) +
      `</RespuestaAutorizacionComprobante></ns2:autorizacionComprobanteResponse>`,
  );

const message = ({ identifier, text, type = "ERROR" }) =>
  `<mensaje><identificador>${identifier}</identificador><mensaje>${text}</mensaje><tipo>${type}</tipo></mensaje>`;

const authorization = ({ state, number, date, xml, messages }) =>
  `<autorizacion><estado>${state}</estado>` +
  (number ? `<numeroAutorizacion>${number}</numeroAutorizacion>` : "") +
  (date ? `<fechaAutorizacion>${date}</fechaAutorizacion>` : "") +
  `<ambiente>PRUEBAS</ambiente>` +
  `<comprobante><![CDATA[${xml}]]></comprobante>` +
  `<mensajes>${messages.map(message).join("")}</mensajes></autorizacion>`;

const EMPTY_INVOICE = '<?xml version="1.0" encoding="UTF-8"?><factura id="comprobante" version="1.1.0"></factura>';

/** Fecha con el desplazamiento de Ecuador (-05:00), como la devuelve el SRI. */
export function sriDate(now = new Date()) {
  const local = new Date(now.getTime() - 5 * 60 * 60 * 1000);
  return local.toISOString().replace("Z", "-05:00");
}

export const received = () => reception("<estado>RECIBIDA</estado><comprobantes/>");

export const returned = (accessKey, { identifier, text }) =>
  reception(
    `<estado>DEVUELTA</estado><comprobantes><comprobante><claveAcceso>${accessKey}</claveAcceso>` +
      `<mensajes>${message({ identifier, text })}</mensajes></comprobante></comprobantes>`,
  );

/** Autorizado en el ambiente de pruebas, con la advertencia 60 que el SRI añade en él. */
export const authorized = (accessKey, xml = EMPTY_INVOICE) =>
  authorizations(
    accessKey,
    authorization({
      state: "AUTORIZADO",
      number: accessKey,
      date: sriDate(),
      xml,
      messages: [
        { identifier: "60", text: "ESTE PROCESO FUE REALIZADO EN EL AMBIENTE DE PRUEBAS", type: "ADVERTENCIA" },
      ],
    }),
  );

export const notAuthorized = (accessKey, { identifier, text }, xml = EMPTY_INVOICE) =>
  authorizations(
    accessKey,
    authorization({ state: "NO AUTORIZADO", date: sriDate(), xml, messages: [{ identifier, text }] }),
  );

export const processing = (accessKey) =>
  authorizations(accessKey, authorization({ state: "EN PROCESO", xml: EMPTY_INVOICE, messages: [] }));

/** El SRI no conoce esa clave: nunca la recibió. */
export const unknown = (accessKey) => authorizations(accessKey);
