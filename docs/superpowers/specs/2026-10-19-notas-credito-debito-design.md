# Notas de crédito y débito — diseño

Fecha: 2026-10-19 · Rama: `p12c-notas-credito-debito` en `api-emite-facil` (salida de
`p12b-modelo-comprobantes`, PR #14) y en `client-factura-facil` (salida de `dev`). Parte 3a del trabajo
acordado con Stiwar: el primer par de comprobantes nuevos sobre el modelo genérico de la parte 2
(`docs/superpowers/specs/2026-10-18-modelo-generico-comprobantes-design.md`).

## Objetivo

Que una empresa emita notas de crédito (04) y de débito (05) de punta a punta: API, XML, firma, SRI,
RIDE, correo y pantallas en la web, sobre una factura emitida en la plataforma o sobre una escrita a
mano.

## Decisiones tomadas

| Tema | Decisión |
|---|---|
| Documento modificado | Una factura de la plataforma (`invoiceId`) o una escrita a mano (código `01`, número y fecha); nunca las dos |
| Factura de la plataforma | Autorizada, del mismo emisor y de la misma empresa; la nota copia el comprador congelado en la factura (no el del catálogo actual) |
| Saldo | La suma de las notas de crédito vigentes de una factura no supera su total |
| Líneas de la nota de crédito | Con factura de la plataforma, salen de sus líneas y solo bajan (cantidad y precio no superan los originales); a mano, salen del catálogo |
| Nota de débito | Motivos (razón, valor, tarifa de IVA cada uno) y una forma de pago por el total |
| Versiones | Nota de crédito 1.1.0, nota de débito 1.0.0 |
| API | Un endpoint por tipo: `POST /credit-notes`, `POST /debit-notes` |
| Web | Botones en el detalle de una factura autorizada y una entrada «Nueva nota» en la navegación |
| Fuera | Borradores locales de notas, reemisión de notas rechazadas, listar en la factura las notas que la modifican, compensaciones, máquina fiscal, información adicional |

## 1. API

### `POST /api/v1/credit-notes`

Cabecera `Idempotency-Key` obligatoria, como `POST /invoices`; responde `202` con el `id`, igual que la
factura. Cuerpo:

```json
{
  "taxpayerId": "uuid", "establishmentCode": "001", "emissionPointCode": "001",
  "issueDate": "2026-10-19",              // opcional; hoy por omisión
  "reason": "Devolución de mercadería",   // 1..300, sin saltos de línea
  "invoiceId": "uuid",                     // factura de la plataforma …
  "modifiedDocument": { "code": "01", "number": "001-001-000000123", "issueDate": "2026-10-01" },
  "customerId": "uuid",                    // … o factura a mano, con su comprador
  "lines": [ … ]                           // 1..200
}
```

- Viene `invoiceId` **o** `modifiedDocument` + `customerId`. Ninguno, o los dos, responde `400
  MODIFIED_DOCUMENT_REQUIRED`.
- **Con `invoiceId`:** cada línea es `{ "invoiceLineNo": 1, "quantity": "1", "unitPrice": "10.00",
  "discount": "0" }` (precio y descuento opcionales: por omisión, el precio de la línea original y su descuento **prorrateado**: descuento original × cantidad / cantidad original, 2 decimales HALF_UP). Código,
  descripción, producto y tarifa de IVA se copian de la línea de la factura. Las líneas no se repiten.
- **A mano:** cada línea es `{ "productId", "quantity", "unitPrice"?, "discount"? }`, como en la factura.
- El cálculo de importes e IVA es el de la factura (`InvoiceCalculator`), con el mismo redondeo.
- El sobre guarda el receptor (comprador), los totales (`total` = `valorModificacion`) y el documento
  modificado: con `invoiceId`, `modified_document_id` más el número y la fecha de esa factura; a mano,
  solo número y fecha.

### `POST /api/v1/debit-notes`

Mismos campos de emisor, punto, fecha, `invoiceId` o `modifiedDocument` + `customerId`. Además:

```json
{
  "reasons": [ { "reason": "Intereses por mora", "value": "12.50", "ivaRateCode": "0" } ],  // 1..20
  "payments": [ { "methodCode": "20", "amount": "12.50" } ]                                  // exactamente 1
}
```

- El IVA se calcula por motivo con su tarifa y se agrupa por tarifa en `impuestos`, con el redondeo de la
  factura. `valorTotal` = suma de valores + IVA.
- El pago debe sumar el total (`PAYMENTS_MISMATCH`), con forma de pago vigente en la fecha
  (`PAYMENT_METHOD_UNKNOWN`), como en la factura.
- Con un comprador consumidor final se aplica el mismo límite que en la factura
  (`FINAL_CONSUMER_LIMIT_EXCEEDED`).

### Reglas comunes

- La fecha del documento modificado no puede ser posterior a la de la nota: `422
  INVALID_MODIFIED_DOCUMENT_DATE`.
- **Factura de la plataforma:** se busca dentro de la empresa (ajena → 404) y se bloquea con `FOR UPDATE`
  antes de reservar el secuencial, para que dos notas sobre la misma factura se decidan de una en una.
  - No autorizada → `422 MODIFIED_INVOICE_NOT_AUTHORIZED`.
  - De otro emisor de la empresa → `422 MODIFIED_INVOICE_OTHER_ISSUER`.
  - No es una factura (`document_type <> '01'`) → `422 MODIFIED_DOCUMENT_NOT_INVOICE`.
- **Saldo (solo nota de crédito con `invoiceId`):** suma de `total` de las notas de crédito con ese
  `modified_document_id` en estados vigentes (todos menos `RETURNED`, `NOT_AUTHORIZED` y
  `XSD_INVALID`) + el total de la nueva ≤ total de la factura; si no, `422 CREDIT_EXCEEDS_INVOICE`.
- **Líneas de la nota de crédito con `invoiceId`:** `invoiceLineNo` inexistente → `422
  CREDIT_LINE_UNKNOWN`; cantidad mayor que la original o precio unitario mayor que el original → `422
  CREDIT_LINE_EXCEEDS_INVOICE`. No se lleva el acumulado por línea: el tope global es el saldo.
- Lo demás es lo de la factura y la parte 2: punto deshabilitado, moneda USD, fecha futura, importes
  máximos, `DOCUMENT_XML_INVALID`, idempotencia.
- La reemisión (`replacesDocumentId`) no se ofrece en las notas.

### Contenido congelado (`content jsonb`)

- `CreditNoteContent(String reason, List<Line> lines, List<Tax> taxes)`; `Line(Integer invoiceLineNo,
  UUID productId, String mainCode, String description, BigDecimal quantity, BigDecimal unitPrice,
  BigDecimal discount, BigDecimal subtotal, String taxCode, String taxRateCode, BigDecimal taxRate,
  BigDecimal taxAmount)`; `Tax(String taxCode, String rateCode, BigDecimal rate, BigDecimal base,
  BigDecimal amount)`.
- `DebitNoteContent(List<Reason> reasons, List<Tax> taxes, List<Payment> payments)`; `Reason(String
  reason, BigDecimal value, String taxCode, String taxRateCode, BigDecimal taxRate, BigDecimal
  taxAmount)`; `Payment(String methodCode, BigDecimal amount)`.

### XML

- `CreditNoteKind` (`CREDIT_NOTE_1_1_0`): `infoTributaria` como la factura; `infoNotaCredito` con fecha,
  dirección del establecimiento, comprador, contribuyente especial y obligado a contabilidad cuando
  aplican, `codDocModificado`, `numDocModificado`, `fechaEmisionDocSustento`, `totalSinImpuestos`,
  `valorModificacion`, `moneda`, `totalConImpuestos` y `motivo`; `detalles` con `codigoInterno`,
  `descripcion`, `cantidad`, `precioUnitario`, `descuento`, `precioTotalSinImpuesto` e `impuestos`.
  Leyendas RIMPE y agente de retención como en la factura.
- `DebitNoteKind` (`DEBIT_NOTE_1_0_0`): `infoNotaDebito` con los mismos datos de cabecera, `impuestos`,
  `valorTotal` y `pagos`; `motivos` con `razon` y `valor`.
- Cada generador tiene un XML esperado de prueba, comparado byte a byte, y se valida contra su XSD.

### RIDE y correo

- Las partes comunes del RIDE de la factura (cabecera con emisor, clave y autorización; pie con totales e
  información de entrega) pasan a un helper (`RideLayout`) que usan los tres. El PDF de la factura no
  cambia de contenido.
- Nota de crédito: título «NOTA DE CRÉDITO», cuadro «Comprobante que se modifica» (factura, número,
  fecha) y «Razón de modificación», detalle y totales como la factura con «Valor modificación».
- Nota de débito: título «NOTA DE DÉBITO», el mismo cuadro, tabla de motivos (razón, valor), totales por
  tarifa, «Valor total» y forma de pago.
- Correo: `displayName` «Nota de crédito» y «Nota de débito», `fileStem` `nota-credito` y
  `nota-debito`; la frase «le ha emitido la nota de crédito electrónica …» ya es correcta.

### Consultas

- `GET /documents/{id}` añade, cuando aplica:
  - `modifiedDocument { code, number, issueDate, documentId }` (notas);
  - `reason` y `lines` (nota de crédito; las líneas llevan además `invoiceLineNo`);
  - `reasons` y `payments` (nota de débito);
  - `creditedTotal` (facturas): suma de las notas de crédito vigentes que la modifican.
- Contrato: esquemas `IssueCreditNoteRequest`, `IssueDebitNoteRequest` y los campos nuevos de
  `Document`; los errores nuevos en las descripciones de 400 y 422.

## 2. Web

- **Navegación:** «Nueva nota» (solo quien factura) → `/notas/nueva?tipo=credito|debito`, con pestañas
  «Nota de crédito» y «Nota de débito».
- **Desde la factura:** el detalle de una factura `AUTHORIZED` muestra «Acreditado $X de $Y» y los
  botones «Nota de crédito» y «Nota de débito», que abren `/notas/nueva?tipo=…&factura=<id>`.
- **Sin `factura`:** modo a mano: número `EEE-PPP-SSSSSSSSS` y fecha de la factura, cliente del catálogo
  (`CatalogPicker`) y el aviso «¿La factura se emitió aquí? Ábrela desde Comprobantes» con enlace.
- **Formulario de nota de crédito:**
  - con factura: tabla de sus líneas con casilla, cantidad y precio editables (tope el original),
    descuento; comprador y número de factura fijos; saldo disponible;
  - a mano: líneas del catálogo, como en la factura;
  - motivo; vista previa de totales con la lógica de `invoicePreview`; «Emitir» solo con todo completo y
    sin superar el saldo.
- **Formulario de nota de débito:** filas de motivo (razón, valor, tarifa de IVA), agregar y quitar,
  forma de pago, vista previa.
- Al emitir, la clave de idempotencia se genera al abrir el formulario y se conserva en los reintentos
  del mismo envío; tras el `202` se va al detalle del comprobante.
- **Detalle:** título por tipo («Nota de crédito 001-001-000000004»), «Modifica a: Factura
  001-001-000000123 del 01/10/2026» (enlace si tiene `documentId`), motivo, líneas o motivos según el
  tipo. «Reemitir» solo aparece en facturas.
- **Listado:** columna «Tipo» (Factura, N. crédito, N. débito; los demás tipos, con su nombre) y un
  selector «Todos los tipos / Facturas / Notas de crédito / Notas de débito» que usa `documentType`.
- La web copia el contrato nuevo y regenera `src/api/schema.ts`; `npm run api:check` debe pasar.

## 3. Pruebas

- API, unitarias: cálculo de nota de crédito y de débito (redondeo por línea, motivo y tarifa); XML de
  cada nota byte a byte y contra su XSD; reglas de líneas contra la factura.
- API, integración:
  - `CreditNoteApiIT`: con factura de la plataforma (líneas copiadas, comprador fijo, tope por línea,
    saldo con dos notas que juntas lo superan, notas rechazadas que no cuentan, factura no autorizada, de
    otro emisor, ajena → 404, no factura), a mano, `400` sin documento modificado o con los dos,
    idempotencia, fecha del documento modificado posterior;
  - `DebitNoteApiIT`: motivos con distintas tarifas, pago que no cuadra, a mano y con factura;
  - las dos notas recorren el pipeline con el SRI simulado hasta `AUTHORIZED`, con RIDE y correo;
  - `creditedTotal` y los campos nuevos de la vista; `OpenApiContractIT`.
- e2e (Playwright, en el API contra la web de la misma rama): emitir una nota de crédito desde el detalle
  de una factura autorizada y verla autorizada en el listado filtrado por tipo.
- Web (vitest): formularios (saldo, tope por línea, habilitación de «Emitir», motivos), detalle por tipo,
  filtro del listado; `npm run api:check`, lint y typecheck.

## 4. Entrega

- `api-emite-facil`: rama `p12c-notas-credito-debito` desde `p12b-modelo-comprobantes`; si #13 y #14 se
  fusionan antes, se rebasa sobre `dev`. PR hacia `dev`.
- `client-factura-facil`: rama `p12c-notas-credito-debito` desde `origin/dev`. PR hacia `dev`.
- Docs del API: 01 (alcance), 05 (flujo de las notas), 11 (endpoints y errores), 14 (versiones que se
  emiten).
