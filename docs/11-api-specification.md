# 11 — Especificación API REST v1

## Convenciones
Base `/api/v1`, JSON UTF-8, JWT de la API para personas o API key (`X-Api-Key`) para otros sistemas, y tenant resuelto por autenticación; timestamps ISO 8601 con offset; dinero como **cadena decimal** en JSON para evitar pérdida de precisión; identificadores UUID. OpenAPI 3.1 contract-first.

## Autenticación, tenant y roles (implementado)
- **Autenticación:** la API emite y valida sus propios JWT. `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/email-verification` y `/auth/password-reset[/request]` son públicos; el resto exige `Authorization: Bearer` con un token de acceso de la API (HS256, emisor y audiencia `einvoice-api`, con expiración). El sujeto es el id del usuario, que debe existir. Detalle en el contrato (`contracts/openapi.yaml`, etiqueta `auth`) y en el doc 10.
- **API keys para otros sistemas** (ERP, punto de venta, tienda en línea): van en la cabecera `X-Api-Key` con el formato `efk_<publicId>_<secreto>`, nunca en `Authorization`. Las crea un `OWNER` o `ADMIN` con `POST /api-keys` (el valor completo solo aparece en esa respuesta) y no caducan; revocarlas corta al momento. Una key actúa siempre como `BILLER` de su empresa, a nombre de quien la creó, aunque esa persona sea `OWNER`: crea clientes y productos (una persona `BILLER` no crea productos), emite y reemite facturas, consulta comprobantes y su estado, descarga XML y RIDE, reenvía el correo y lee `GET /tenants/current`. No necesita `X-Tenant-Id`; si lo envía, debe ser su empresa (`403 TENANT_FORBIDDEN`). La configuración (emisores, establecimientos, puntos, secuenciales, certificados, webhooks, paso a producción y las propias API keys) le responde `403 ROLE_FORBIDDEN`. En el contrato, las operaciones que la aceptan declaran el esquema `apiKey` además de `bearerAuth`.
  - Key mal formada, inexistente, con otro secreto o revocada: `401 UNAUTHENTICATED`, con el mismo cuerpo en todos los casos. También `401` si la empresa está suspendida o quien la creó ya no tiene en ella una membresía activa con un rol que factura.
  - `X-Api-Key` y `Authorization` en la misma petición: `400 CREDENTIALS_AMBIGUOUS`, aunque la key venga vacía.
  - Lo que solo hace una persona responde `403 API_KEY_NOT_ALLOWED`: `POST /tenants`, `GET /tenants`, `GET /auth/me`, `POST /auth/password` y `POST /auth/email-verification/resend`.
  - Tiene su propio cupo de peticiones por minuto, aparte del de su creador.
- **Tenant activo:** se resuelve con las membresías del usuario. Si pertenece a una sola empresa no envía nada; si pertenece a varias envía la cabecera `X-Tenant-Id` (`400 TENANT_REQUIRED` si falta, `400 TENANT_HEADER_INVALID` si no es un UUID). Una empresa ajena, inexistente o suspendida responde `403 TENANT_FORBIDDEN` con el mismo cuerpo. El `tenantId` nunca se lee del cuerpo.
- **Roles por membresía:** `OWNER`, `ADMIN`, `BILLER`, `AUDITOR`. Todos leen; solo `OWNER` y `ADMIN` crean emisores, establecimientos y puntos (`403 ROLE_FORBIDDEN` en otro caso). Las API keys no tienen rol propio: usan el de `BILLER` y, además, crean productos.
- **Aislamiento:** un recurso de otra empresa responde `404 NOT_FOUND` igual que uno inexistente.
- **Correlación:** toda respuesta lleva `X-Correlation-ID`; se respeta el enviado por el cliente si cumple `[A-Za-z0-9._-]{1,64}`. El mismo valor aparece en cada línea de log de la petición y en el cuerpo de los errores.
- **Texto libre:** nombres y direcciones no admiten caracteres de control (`400 VALIDATION_FAILED`).

## Endpoints implementados
El contrato vigente es [`contracts/openapi.yaml`](../contracts/openapi.yaml); un test falla si deja de coincidir con los controladores.

| Método | Ruta | Quién | Resultado |
|---|---|---|---|
| POST | `/tenants` | cualquier usuario autenticado | 201; el creador queda como `OWNER` |
| GET | `/tenants` | cualquier usuario autenticado | empresas donde tiene membresía activa |
| GET | `/tenants/current` | miembro o API key | empresa activa y rol |
| POST | `/webhooks/subscriptions` | `OWNER`, `ADMIN` | 201 con el secreto de firma, que solo aparece aquí; `422 WEBHOOK_URL_NOT_ALLOWED`, `409 WEBHOOK_LIMIT_REACHED`, `409 WEBHOOKS_DISABLED` |
| GET | `/webhooks/subscriptions` | `OWNER`, `ADMIN` | suscripciones de la empresa, sin su secreto |
| PUT | `/webhooks/subscriptions/{id}` | `OWNER`, `ADMIN` | cambia dirección, eventos y descripción; apaga o enciende (`enabled`) |
| DELETE | `/webhooks/subscriptions/{id}` | `OWNER`, `ADMIN` | 204; borra también su historial de avisos |
| POST | `/webhooks/subscriptions/{id}/test` | `OWNER`, `ADMIN` | 202 con el aviso de prueba anotado; `409 WEBHOOK_TEST_PENDING`, `409 WEBHOOK_SUBSCRIPTION_DISABLED`, `409 WEBHOOKS_DISABLED` |
| POST | `/webhooks/subscriptions/{id}/secret` | `OWNER`, `ADMIN` | secreto nuevo (solo aparece aquí); el anterior vale 24 horas más |
| GET | `/webhooks/subscriptions/{id}/deliveries` | `OWNER`, `ADMIN` | avisos de la suscripción, paginados, los más recientes primero |
| POST | `/webhooks/deliveries/{id}/retry` | `OWNER`, `ADMIN` | 202: vuelve a enviar un aviso fallido; `409 WEBHOOK_DELIVERY_NOT_RETRYABLE`, `409 WEBHOOK_SUBSCRIPTION_DISABLED` |
| POST | `/taxpayers` | `OWNER`, `ADMIN` | 201; `409 RUC_ALREADY_REGISTERED` si el RUC ya existe en la plataforma |
| GET | `/taxpayers`, `/taxpayers/{id}` | miembro o API key | emisores de la empresa activa |
| POST | `/taxpayers/{taxpayerId}/establishments` | `OWNER`, `ADMIN` | 201; `409 ESTABLISHMENT_CODE_TAKEN` |
| GET | `/taxpayers/{taxpayerId}/establishments` | miembro o API key | lista ordenada por código |
| POST | `/emission-points` | `OWNER`, `ADMIN` | 201; `409 EMISSION_POINT_CODE_TAKEN` |
| GET | `/emission-points?establishmentId=` | miembro o API key | lista ordenada por código |
| PUT | `/emission-points/{id}/sequence` | `OWNER`, `ADMIN` | fija el próximo secuencial antes del primer comprobante; `409 SEQUENCE_ALREADY_USED` después |
| GET | `/catalogs/identification-types`, `/catalogs/payment-methods`, `/catalogs/tax-rates` | cualquier usuario autenticado o API key | catálogos de la ficha técnica |
| POST | `/customers` | `OWNER`, `ADMIN`, `BILLER`, API key | 201; `409 CUSTOMER_ALREADY_EXISTS`; `422 IDENTIFICATION_TYPE_UNKNOWN` o `IDENTIFICATION_INVALID` |
| GET | `/customers?page=&size=&q=`, `/customers/{id}` | miembro o API key | página `{items, page, size, total}`; `q` busca por nombre o prefijo de identificación |
| POST | `/products` | `OWNER`, `ADMIN` o API key | 201; `409 PRODUCT_SKU_TAKEN`; `422 TAX_RATE_UNKNOWN` |
| GET | `/products?page=&size=&q=`, `/products/{id}` | miembro o API key | página; `q` busca en nombre o código |
| POST | `/invoices` | `OWNER`, `ADMIN`, `BILLER`, API key | 202 `{id, state, statusUrl}` con el documento en `RESERVED` |
| POST | `/credit-notes` | `OWNER`, `ADMIN`, `BILLER`, API key | 202 `{id, state, statusUrl}`; reglas en [Notas de crédito y de débito](#notas-de-crédito-y-de-débito) |
| POST | `/debit-notes` | `OWNER`, `ADMIN`, `BILLER`, API key | 202 `{id, state, statusUrl}`; reglas en [Notas de crédito y de débito](#notas-de-crédito-y-de-débito) |
| GET | `/documents?page=&size=&documentType=`, `/documents/{id}` | miembro o API key | resumen paginado, con filtro opcional por tipo (`01`, `03`, `04`, `05`, `06`, `07`; otro código, `400 MALFORMED_REQUEST`); documento completo con totales, líneas y pagos (ver los campos de las notas en la sección siguiente). En la guía de remisión `subtotal`, `totalDiscount`, `totalTax` y `total` salen `null` |
| GET | `/documents/{id}/artifacts/{kind}` | miembro o API key | `xml-unsigned`, `xml-signed`, `sri-authorization` (respuesta del SRI), `xml-authorized` (XML autorizado que se entrega) o `ride` (PDF), con su SHA-256 en `ETag`; 404 si aún no existe |
| POST | `/documents/{id}/retry` | `OWNER`, `ADMIN`, `BILLER`, API key | 202 `{id, state}`: devuelve el comprobante a la cola sin cambiar su estado; `409 DOCUMENT_NOT_RETRYABLE` fuera de `RESERVED`, `GENERATED`, `SIGNATURE_FAILED`, `SIGNED`, `SENDING` y `RECEIVED` |
| GET | `/documents/{id}/sri-attempts` | miembro o API key | llamadas al SRI del comprobante, la más reciente primero, con estado literal, resultado y mensajes; nunca la respuesta cruda |
| GET | `/documents/{id}/deliveries` | miembro o API key | entregas al receptor, la más reciente primero: destinatario, estado, intentos, último error y fechas |
| POST | `/documents/{id}/deliveries` | `OWNER`, `ADMIN`, `BILLER`, API key | 202: nuevo envío por correo; cuerpo opcional `{"email"}` para otra dirección. `409 DOCUMENT_NOT_AUTHORIZED`, `DELIVERY_DISABLED` o `DELIVERY_LIMIT_REACHED`; `422 DELIVERY_RECIPIENT_MISSING` |
| POST | `/certificates` | `OWNER`, `ADMIN` | 201 con los metadatos del certificado; `422` con `CERTIFICATE_UNREADABLE`, `CERTIFICATE_NO_PRIVATE_KEY`, `CERTIFICATE_AMBIGUOUS`, `CERTIFICATE_KEY_MISMATCH`, `CERTIFICATE_EXPIRED`, `CERTIFICATE_NOT_YET_VALID` o `CERTIFICATE_KEY_UNSUPPORTED` |
| GET | `/certificates?taxpayerId=` | miembro o API key | metadatos del certificado activo y de los reemplazados; nunca el contenido |
| POST | `/api-keys` | `OWNER`, `ADMIN` | 201 `{id, name, publicId, key, createdAt}`; `key` es el valor completo y solo aparece aquí. Nombre de 1 a 80 caracteres, único entre las activas sin distinguir mayúsculas (`409 API_KEY_NAME_TAKEN`); hasta 10 activas por empresa (`409 API_KEY_LIMIT`) |
| GET | `/api-keys` | `OWNER`, `ADMIN` | keys de la empresa sin su valor `{id, name, publicId, createdBy, createdAt, lastUsedAt, revokedAt}`, primero las activas; `createdBy` es el correo del creador |
| DELETE | `/api-keys/{id}` | `OWNER`, `ADMIN` | 204: revoca al momento y sin vuelta atrás; una ya revocada responde 204 sin cambios; `404 API_KEY_NOT_FOUND` si no es de la empresa |

### Reglas de `POST /invoices`
- **Idempotencia:** cabecera `Idempotency-Key` obligatoria (hasta 100 caracteres: letras, dígitos y `. _ : -`). Misma clave y mismo cuerpo devuelven el mismo documento; misma clave con otro cuerpo, `409 IDEMPOTENCY_KEY_REUSED`. El orden de las claves y los espacios del JSON no cuentan. Una petición que falla no consume la clave.
- **Importes y cantidades:** cadenas decimales sin signo ni exponente. Cantidad y precio unitario hasta 6 decimales; descuentos y pagos hasta 2. Las respuestas devuelven siempre cadenas con escala fija.
- **Cálculo (ficha v2.34, 9.16 y 9.17):** el subtotal de línea es cantidad × precio − descuento redondeado a 2 decimales; el IVA de cada tarifa se calcula sobre la suma de las bases de esa tarifa. El total lo calcula el servidor.
- **Pagos:** al menos uno, con forma de pago vigente del catálogo; su suma debe ser igual al total (`422 PAYMENTS_MISMATCH`). Un pago en cero solo se admite si el comprobante entero vale cero (`422 INVALID_PAYMENT`).
- **Fecha de emisión:** por defecto hoy en Ecuador. No puede ser futura ni anterior a la del último comprobante del punto de emisión (`422 INVALID_ISSUE_DATE`).
- **Consumidor final:** una factura a identificación tipo `07` no puede superar 50.00 USD (`422 FINAL_CONSUMER_LIMIT_EXCEEDED`); el monto es configurable.
- **Otros 422:** `UNSUPPORTED_CURRENCY` (solo USD), `EMISSION_POINT_DISABLED`, `INVALID_LINE`, `AMOUNT_TOO_LARGE` (ningún importe del comprobante puede pasar de 999999999999.99), `PAYMENT_METHOD_UNKNOWN`, `SEQUENCE_EXHAUSTED`, `DOCUMENT_XML_INVALID` (el XML generado no pasa el esquema del SRI; el detalle se trunca a 500 caracteres y no se consume secuencial). Emisor, establecimiento, punto, cliente o producto inexistente o de otra empresa: `404`. Si una petición tiene varios problemas, se comprueban antes el emisor, el punto de emisión, la moneda y la fecha que el cliente: un cliente desconocido junto con un punto deshabilitado responde `422` por el punto.
- **Clientes:** la identificación `9999999999999` solo se admite con el tipo `07`; registrarla como RUC u otro tipo responde `422 IDENTIFICATION_INVALID`.
- **Listados:** `size` de 1 a 100, `page` de 0 a 100000 y `q` de hasta 100 caracteres sin caracteres de control; fuera de eso, `400 MALFORMED_REQUEST`.
- **Emisores:** el RUC debe ser de diez dígitos seguidos de `001`. Al darlo de alta se pueden indicar `accountingRequired`, `specialTaxpayerCode`, `withholdingAgentCode` y `rimpeRegime` (`NONE`, `EMPRENDEDOR`, `NEGOCIO_POPULAR`), que el comprobante lleva como leyendas. `NEGOCIO_POPULAR` se rechaza por ahora con `422 RIMPE_REGIME_UNSUPPORTED` (doc 14).
- **Certificados:** el `.p12` viaja en Base64 (hasta 64 KB) con su contraseña, por HTTPS; se guarda cifrado y no se puede volver a descargar.
- **Estados de un comprobante:** `RESERVED`, `GENERATED`, `SIGNED`, `SENDING`, `RECEIVED`, `AUTHORIZED`, y los de fallo `XSD_INVALID`, `SIGNATURE_FAILED`, `RETURNED` y `NOT_AUTHORIZED` (doc 05). `POST /invoices` solo confirma la aceptación para procesar; la autorización fiscal existe cuando el estado es `AUTHORIZED`, y entonces el comprobante trae `authorizationNumber` y `authorizedAt` (UTC).
- **Reemisión:** el comprobante reemplazado debe ser del mismo tipo que el nuevo; si no, `409 REPLACED_DOCUMENT_OTHER_TYPE`.
- **Alcance actual:** factura (`01`), nota de crédito (`04`) y nota de débito (`05`) con IVA; sin ICE, IRBPNR, propina, reembolsos ni exportación.

### Notas de crédito y de débito
Las dos exigen `Idempotency-Key` y responden `202` como la factura; la reemisión (`replacesDocumentId`) no se ofrece. Los campos de emisor, punto y `issueDate` son los de la factura.

- **`POST /credit-notes`:** `{taxpayerId, establishmentCode, emissionPointCode, issueDate?, reason, invoiceId | (modifiedDocument + customerId), lines}`. `reason` de 1 a 300 caracteres sin saltos de línea; `lines` de 1 a 200.
  - Con `invoiceId`, cada línea es `{invoiceLineNo, quantity, unitPrice?, discount?}`: el código, la descripción, el producto y el IVA salen de la línea de la factura; sin `unitPrice` se usa el original y, sin `discount`, el descuento original **prorrateado** (descuento × cantidad devuelta / cantidad original, 2 decimales HALF_UP). La cantidad y el precio unitario no pueden superar los originales, ni el descuento bajar de ese prorrateado: si no, `422 CREDIT_LINE_EXCEEDS_INVOICE`. El comprador es el congelado en la factura: un `customerId` en el cuerpo responde `400 MODIFIED_DOCUMENT_REQUIRED`.
  - A mano, `modifiedDocument` es `{code: "01", number: "EEE-PPP-SSSSSSSSS", issueDate}` y cada línea `{productId, quantity, unitPrice?, discount?}`, como en la factura.
- **`POST /debit-notes`:** los mismos campos de documento modificado, más `reasons` (1 a 20, cada una `{reason, value, ivaRateCode}`) y `payments` (exactamente uno, que suma el total).
- **Errores:** `400 MODIFIED_DOCUMENT_REQUIRED` (ni `invoiceId` ni documento a mano, o los dos, o `customerId` con `invoiceId`); `404` si la factura es de otra empresa; `422`: `INVALID_MODIFIED_DOCUMENT_DATE`, `MODIFIED_INVOICE_NOT_AUTHORIZED`, `MODIFIED_INVOICE_OTHER_ISSUER`, `MODIFIED_DOCUMENT_NOT_INVOICE`, `CREDIT_EXCEEDS_INVOICE`, `CREDIT_LINE_UNKNOWN`, `CREDIT_LINE_EXCEEDS_INVOICE`; en la de débito además `PAYMENTS_MISMATCH`, `PAYMENT_METHOD_UNKNOWN`, `TAX_RATE_UNKNOWN` y `FINAL_CONSUMER_LIMIT_EXCEEDED`. El resto es lo de la factura.
- **`GET /documents/{id}`** añade, cuando aplica y `null` si no: `modifiedDocument {code, number, issueDate, documentId}` (notas; `documentId` es nulo si la factura se escribió a mano); `reason` (nota de crédito); `reasons [{reason, value, taxRateCode, taxRate, taxAmount}]` (nota de débito, cuyo pago sale en `payments` y su IVA en `taxes`); `creditedTotal` (solo facturas: suma de las notas de crédito vigentes, `"0.00"` sin notas). Las líneas de una nota de crédito salen en `lines` con `invoiceLineNo`, que es `null` en las demás.

## Endpoints propuestos (pendientes)
| Método | Ruta | Alcance | Resultado |
|---|---|---|---|
| GET | `/documents` | document.read | filtros por estado, fecha y cliente (el listado paginado ya existe) |
| GET | `/documents/{id}/events` | document.audit | trazabilidad |
| GET | `/documents/{id}/ride?format=` | document.read | otros formatos del RIDE (el PDF ya se descarga como artefacto) |
| POST | `/documents/{id}/resend` | document.reconcile | 202: corregir y reenviar un comprobante devuelto o no autorizado con su misma clave (el reintento sin corrección ya existe como `/retry`) |

## Crear factura
```http
POST /api/v1/invoices
Authorization: Bearer <access-token>
Idempotency-Key: b4a7ea99-0d1d-44aa-9d26-28c43fda89a2
Content-Type: application/json

{
  "taxpayerId": "cc88a07b-a523-416e-8718-4d4be8c22ba7",
  "establishmentCode": "001",
  "emissionPointCode": "001",
  "customerId": "a03c18ee-36c9-4ba6-bb3d-b2537c0e3581",
  "issueDate": "2026-10-07",
  "currency": "USD",
  "lines": [{"productId":"ad6e9ed5-d9a2-430c-8534-f9c907a2b853","quantity":"2.000000","unitPrice":"10.00","discount":"0.00"}],
  "payments": [{"methodCode":"20","amount":"23.00"}]
}
```
`payments.amount` **y la tasa de impuestos aquí son meramente ilustrativos**: el total se calcula en servidor con el catálogo vigente; no confiar en cálculos del cliente.

```json
{
  "id":"1376d8b2-b29a-414e-8555-3bf4779b7d2c",
  "state":"RESERVED",
  "statusUrl":"/api/v1/documents/1376d8b2-b29a-414e-8555-3bf4779b7d2c"
}
```

## Webhooks salientes
Implementados en el Plan 6b; vienen apagados (`EINVOICE_WEBHOOKS_ENABLED`). Una empresa registra hasta 10 direcciones `https` y elige qué eventos quiere en cada una.

| Evento | Cuándo |
|---|---|
| `document.received` | el SRI recibió el comprobante (`RECEIVED`) |
| `document.authorized` | el SRI lo autorizó (`AUTHORIZED`) |
| `document.rejected` | el SRI lo devolvió o no lo autorizó; `document.state` dice cuál (`RETURNED` o `NOT_AUTHORIZED`) |
| `document.delivered` | salió un correo de entrega al cliente |

Cada aviso es un `POST` con este cuerpo, que es el mismo en todos sus reintentos:

```json
{
  "eventId": "3f2b0c1e-0d6e-4a57-9c1b-7a3c5d9e8f10",
  "eventType": "document.authorized",
  "occurredAt": "2026-10-16T15:04:05Z",
  "tenantId": "0b7c7f0e-5d1c-4b11-9a53-2f6f4d0d7c55",
  "document": {
    "id": "1376d8b2-b29a-414e-8555-3bf4779b7d2c",
    "documentType": "01",
    "number": "001-001-000000123",
    "accessKey": "1610202601179001234500110010010000001231234567811",
    "state": "AUTHORIZED",
    "authorizationNumber": "1610202601179001234500110010010000001231234567811",
    "authorizedAt": "2026-10-16T15:04:03Z"
  }
}
```

`authorizationNumber` y `authorizedAt` son `null` salvo en los avisos de un comprobante autorizado. `state` es el estado que se anuncia, aunque el comprobante haya avanzado cuando el aviso sale. No viajan datos del cliente, importes, XML ni mensajes del SRI.

**Firma** (convención *Standard Webhooks*). Cabeceras: `webhook-id` (igual a `eventId`, y el mismo en cada reintento: sirve para descartar repetidos), `webhook-timestamp` (segundos Unix del intento) y `webhook-signature`, con una o dos firmas separadas por un espacio (dos durante las 24 horas siguientes a rotar el secreto). Cada firma es `v1,` más el Base64 del HMAC-SHA256 de `webhook-id + "." + webhook-timestamp + "." + cuerpo`, con la clave que resulta de decodificar de Base64 el secreto sin su prefijo `whsec_`. Para verificar:

```python
import base64, hashlib, hmac, time

def es_nuestro(secreto: str, cabeceras: dict, cuerpo: bytes) -> bool:
    clave = base64.b64decode(secreto.removeprefix("whsec_"))
    firmado = f"{cabeceras['webhook-id']}.{cabeceras['webhook-timestamp']}.".encode() + cuerpo
    esperada = "v1," + base64.b64encode(hmac.new(clave, firmado, hashlib.sha256).digest()).decode()
    reciente = abs(time.time() - int(cabeceras["webhook-timestamp"])) < 300
    return reciente and any(hmac.compare_digest(esperada, firma) for firma in cabeceras["webhook-signature"].split())
```

**Entrega.** Al menos una vez, no exactamente una: el receptor debe tolerar repetidos y avisos fuera de orden. Cuenta como entregado cualquier respuesta 2xx recibida en 10 segundos; el cuerpo de la respuesta no se lee y las redirecciones no se siguen. Si no, se reintenta con una espera que se duplica (30 s, 1 min, 2 min...) hasta un tope de 6 h: 14 intentos en total, el último unas 26 horas después del primero. Después el aviso queda `FAILED` y se puede reintentar a mano. Una dirección que no resuelve o que ha dejado de ser pública se trata igual: no se conecta, y se reintenta. Una suscripción se apaga sola cuando acumula 100 intentos fallidos seguidos **y** lleva más de 3 días sin un solo envío correcto; una caída corta, aunque afecte a muchos comprobantes, no la apaga. La dirección debe ir en ASCII (lo demás, codificado con `%`) y su nombre no puede acabar en punto.


**Aviso de prueba** (Plan 6c). `POST /webhooks/subscriptions/{id}/test` envía a una dirección activa un aviso `webhook.test`, firmado igual que los demás y por la misma cola, sin emitir nada. Su cuerpo no lleva comprobante:

```json
{"eventId": "…", "eventType": "webhook.test", "occurredAt": "2026-10-14T05:00:00Z", "tenantId": "…", "subscriptionId": "…"}
```

Se intenta una sola vez: no se reintenta (para probar de nuevo se pide otro) y su resultado no cuenta para apagar la suscripción. Mientras uno esté en camino no se admite otro (`409 WEBHOOK_TEST_PENDING`). Aparece en el historial con `eventType` `webhook.test` y `documentId` nulo.

**Reparto y retención.** Como mucho dos avisos de una misma empresa se envían a la vez (`einvoice.webhooks.max-concurrent-per-tenant`). Los demás esperan unos segundos sin gastar un intento, de modo que un receptor lento no retrasa los avisos de otras empresas. El historial guarda los avisos entregados o fallidos 90 días (`einvoice.webhooks.history-retention`); el worker borra cada hora los más antiguos. Los pendientes no se borran.

## Políticas
`Idempotency-Key` obligatorio en POST de emisión; 409 al variar payload; 429 por tenant y token; `X-Correlation-ID` opcional para diagnósticos; no exponer SOAP interno ni XML de otros tenants. `GET /documents/{id}` refleja que autorizado y recibido son estados distintos.

## OpenAPI y generación
Archivo OpenAPI versionado en `/contracts/openapi.yaml`; cada plan añade ahí sus rutas junto con los controladores, y `OpenApiContractIT` comprueba que ambos describen las mismas. Pendiente: generar el cliente TS para Next.js (Plan 7) y validar también los esquemas de respuesta.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
