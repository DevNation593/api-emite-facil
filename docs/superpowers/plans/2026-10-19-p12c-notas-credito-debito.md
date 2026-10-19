# Notas de crédito y débito — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emitir notas de crédito (04) y de débito (05) de punta a punta —API, XML, SRI, RIDE, correo y web— sobre una factura de la plataforma o una escrita a mano.

**Architecture:** En el API, un paquete `com.example.einvoice.notes` con el contenido, el cálculo, el XML, el `DocumentKind`, el servicio y el controlador de cada nota, todos sobre `DocumentIssuer` y `JsonContentKind` de la parte 2. El RIDE de la factura se parte en un `RideLayout` común. En la web, una página `/notas/nueva` con un formulario por tipo, botones en el detalle de la factura y el tipo en el listado y el detalle.

**Tech Stack:** API: Java 21+, Spring Boot 4, JdbcClient, Jackson 3, OpenPDF, PostgreSQL/Flyway, JUnit 5/AssertJ/Testcontainers, Playwright (e2e). Web: Next.js 16, React 19, openapi-fetch, Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-19-notas-credito-debito-design.md`

## Global Constraints

- API: worktree `D:\Proyectos Personales\.worktrees\backend-p12c-notas`, rama `p12c-notas-credito-debito` (salida de `p12b-modelo-comprobantes`). Web: worktree `D:\Proyectos Personales\.worktrees\client-p12c-notas`, rama `p12c-notas-credito-debito` salida de `origin/dev` (la crea la tarea 7). Nunca tocar `D:\Proyectos Personales\backend-emite-facil` ni `D:\Proyectos Personales\client-factura-facil` (trabajo sin commit de otra sesión). Nunca push.
- Comentarios, mensajes y textos de pantalla en español; código en inglés.
- Maven en Windows: `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/tmp/jdksock ./mvnw ...` en la misma llamada. Las `*IT` necesitan Docker Desktop (no arrancarlo nunca).
- Web: `npm run lint`, `npm run typecheck`, `npm test`, `npm run api:check` deben pasar; el contrato se copia de `contracts/openapi.yaml` del API y se regenera con `npm run api:generate`.
- Versiones: nota de crédito `DocumentSchema.CREDIT_NOTE_1_1_0`, nota de débito `DocumentSchema.DEBIT_NOTE_1_0_0`.
- Nombres: `displayName` «Nota de crédito» / «Nota de débito»; `fileStem` `nota-credito` / `nota-debito`; auditoría `CREDIT_NOTE_RESERVED` / `DEBIT_NOTE_RESERVED`.
- Errores (exactos): `400 MODIFIED_DOCUMENT_REQUIRED`; `422 INVALID_MODIFIED_DOCUMENT_DATE`, `MODIFIED_INVOICE_NOT_AUTHORIZED`, `MODIFIED_INVOICE_OTHER_ISSUER`, `MODIFIED_DOCUMENT_NOT_INVOICE`, `CREDIT_EXCEEDS_INVOICE`, `CREDIT_LINE_UNKNOWN`, `CREDIT_LINE_EXCEEDS_INVOICE`; factura ajena → 404.
- Notas vigentes para el saldo: todos los estados menos `RETURNED`, `NOT_AUTHORIZED` y `XSD_INVALID`.
- Límites: `reason` 1..300 sin saltos de línea; líneas de nota de crédito 1..200; motivos 1..20; exactamente 1 pago en la nota de débito.
- Commits con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Dos notas de crédito simultáneas sobre la misma factura** no deben superar juntas su total: el bloqueo de la factura antes de reservar lo impide. Lo prueba la tarea 4 (`concurrentNotesNeverExceedTheInvoice`).
2. **Una nota de crédito rechazada por el SRI no consume saldo:** tras `NOT_AUTHORIZED`, se puede volver a acreditar el total. Lo prueba la tarea 4.
3. **Factura de la plataforma emitida con otro comprador que el que se envía:** con `invoiceId`, un `customerId` en el cuerpo se rechaza (`400 MODIFIED_DOCUMENT_REQUIRED`), el comprador es siempre el de la factura. Lo prueba la tarea 4.
4. **Precio unitario con más decimales que el original o descuento mayor que el subtotal de la línea** se rechazan sin llegar al XSD (el cálculo de la factura ya lo hace: `INVALID_LINE`). Lo prueba la tarea 4.
5. **La pantalla de la nota de crédito con una factura que ya no tiene saldo** muestra «Saldo disponible $0.00» y deja «Emitir» deshabilitado. Lo prueba la tarea 8.

---

### Task 1: Nota de crédito — contenido, cálculo y XML

**Files:**
- Create: `src/main/java/com/example/einvoice/notes/CreditNoteContent.java`, `notes/NoteTax.java`, `notes/CreditNoteXmlBuilder.java`
- Test: `src/test/java/com/example/einvoice/notes/CreditNoteXmlBuilderTest.java`

**Interfaces:**
- Consumes: `billing.DocumentEnvelope` (con `modified()` no nulo), `xml.XsdValidator`, `xml.DocumentSchemas`, `xml.EnvelopeRules`; como referencia de formato: `xml.InvoiceXmlBuilder` (mismos formatos de fecha, decimales, escapes, leyendas RIMPE y agente de retención, campo adicional del RUC del proveedor).
- Produces:
  ```java
  public record NoteTax(String taxCode, String rateCode, BigDecimal rate, BigDecimal base, BigDecimal amount) {}
  public record CreditNoteContent(String reason, List<Line> lines, List<NoteTax> taxes) {
  	public record Line(Integer invoiceLineNo, UUID productId, String mainCode, String description,
  			BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal subtotal,
  			String taxCode, String taxRateCode, BigDecimal taxRate, BigDecimal taxAmount) {}
  }
  public final class CreditNoteXmlBuilder { public static byte[] build(DocumentEnvelope envelope, CreditNoteContent content); }
  ```

- [ ] **Step 1: Pruebas** en `CreditNoteXmlBuilderTest` con un sobre y contenido de ejemplo (dos líneas, IVA 15 % y 0 %, documento modificado `01` `001-001-000000123` del 01/10/2026, motivo «Devolución de mercadería»):
  - `producesExpectedXml`: igual a una constante `EXPECTED` escrita a mano siguiendo el XSD 1.1.0 (orden de elementos: `infoTributaria`, `infoNotaCredito` [`fechaEmision`, `dirEstablecimiento`, `tipoIdentificacionComprador`, `razonSocialComprador`, `identificacionComprador`, `contribuyenteEspecial`?, `obligadoContabilidad`?, `codDocModificado`, `numDocModificado`, `fechaEmisionDocSustento`, `totalSinImpuestos`, `valorModificacion`, `moneda`, `totalConImpuestos`, `motivo`], `detalles`, `infoAdicional`?), raíz `<notaCredito id="comprobante" version="1.1.0">`.
  - `isValidAgainstXsdAndEnvelopeRules`: `XsdValidator.validate(xml, CREDIT_NOTE_1_1_0)` sin errores y `EnvelopeRules.check(xml, CREDIT_NOTE_1_1_0)` vacío.
  - `sameInputGivesSameBytes`, `escapesSpecialCharacters` (`&`, `<` en motivo y descripción).
- [ ] **Step 2: Correr** `./mvnw -B -q test -Dtest=CreditNoteXmlBuilderTest` — Expected: FAIL (no compila).
- [ ] **Step 3: Implementar** los records y `CreditNoteXmlBuilder.build` con el mismo escritor XML que `InvoiceXmlBuilder` (extraer a un helper del paquete `xml` lo que haga falta compartir, sin cambiar el XML de la factura). `valorModificacion` = `envelope.totals().total()`; `totalSinImpuestos` = `totals().subtotal()`; `codigoInterno` = `mainCode`.
- [ ] **Step 4: Correr** `CreditNoteXmlBuilderTest` e `InvoiceXmlBuilderTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(notes): contenido y XML de la nota de crédito`

### Task 2: Nota de débito — contenido, cálculo y XML

**Files:**
- Create: `notes/DebitNoteContent.java`, `notes/DebitNoteCalculator.java`, `notes/DebitNoteXmlBuilder.java`
- Test: `src/test/java/com/example/einvoice/notes/DebitNoteCalculatorTest.java`, `notes/DebitNoteXmlBuilderTest.java`

**Interfaces:**
- Consumes: `NoteTax` (tarea 1), `billing.DocumentEnvelope`.
- Produces:
  ```java
  public record DebitNoteContent(List<Reason> reasons, List<NoteTax> taxes, List<Payment> payments) {
  	public record Reason(String reason, BigDecimal value, String taxCode, String taxRateCode, BigDecimal taxRate, BigDecimal taxAmount) {}
  	public record Payment(String methodCode, BigDecimal amount) {}
  }
  public final class DebitNoteCalculator {
  	public record Input(String reason, BigDecimal value, String ivaRateCode, BigDecimal ivaRate) {}
  	public record Result(List<DebitNoteContent.Reason> reasons, List<NoteTax> taxes, BigDecimal subtotal, BigDecimal totalTax, BigDecimal total) {}
  	public static Result calculate(List<Input> inputs);
  }
  public final class DebitNoteXmlBuilder { public static byte[] build(DocumentEnvelope envelope, DebitNoteContent content); }
  ```

- [ ] **Step 1: Pruebas.**
  - `DebitNoteCalculatorTest.groupsTaxByRate`: motivos `10.00` al 15 %, `5.00` al 15 % y `3.00` al 0 % → `taxes` con base `15.00` IVA `2.25` (tarifa `4`) y base `3.00` IVA `0.00` (tarifa `0`), `subtotal` `18.00`, `totalTax` `2.25`, `total` `20.25`.
  - `DebitNoteCalculatorTest.roundsLikeTheInvoice`: el IVA por tarifa se redondea igual que `InvoiceCalculator` (mismo modo y escala; caso `0.33` × 3 al 15 %).
  - `DebitNoteCalculatorTest.rejectsZeroOrNegativeValue` → `IllegalArgumentException`.
  - `DebitNoteXmlBuilderTest.producesExpectedXml` (constante `EXPECTED` según el XSD 1.0.0: `infoNotaDebito` con `codDocModificado`, `numDocModificado`, `fechaEmisionDocSustento`, `totalSinImpuestos`, `impuestos`, `valorTotal`, `pagos`; luego `motivos`), `isValidAgainstXsdAndEnvelopeRules`, `sameInputGivesSameBytes`.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar.** El redondeo reutiliza las funciones de `InvoiceCalculator` (hacerlas accesibles en el paquete o extraerlas a un helper `billing.TaxMath` sin cambiar resultados de la factura).
- [ ] **Step 4: Correr** las pruebas de la tarea y `TaxRoundingTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(notes): cálculo y XML de la nota de débito`

### Task 3: RIDE común y los dos `DocumentKind`

**Files:**
- Create: `delivery/RideLayout.java`, `notes/CreditNoteRide.java`, `notes/DebitNoteRide.java`, `notes/CreditNoteKind.java`, `notes/DebitNoteKind.java`
- Modify: `delivery/RidePdfBuilder.java` (usa `RideLayout`)
- Test: `src/test/java/com/example/einvoice/notes/NoteRideTest.java`, `notes/NoteKindsTest.java`; `delivery/RidePdfBuilderTest.java` sin cambios en lo que comprueba

**Interfaces:**
- Consumes: tareas 1 y 2; `billing.JsonContentKind<C>` (ctor `(Class<C>, FiscalDocumentRepository)`), `billing.RideEnvelope`.
- Produces: `RideLayout` con los bloques comunes que hoy son privados en `RidePdfBuilder` (cabecera con emisor/clave/autorización/código de barras, cuadro del receptor, totales por tarifa, pie); `CreditNoteRide.build(RideEnvelope, CreditNoteContent): byte[]`; `DebitNoteRide.build(RideEnvelope, DebitNoteContent, Map<String,String> paymentMethodNames): byte[]`; `@Component CreditNoteKind extends JsonContentKind<CreditNoteContent>` y `@Component DebitNoteKind extends JsonContentKind<DebitNoteContent>` con los nombres de Global Constraints.

- [ ] **Step 1: Pruebas.**
  - `NoteRideTest.creditNoteShowsModifiedDocumentAndReason`: el texto extraído (como en `RidePdfBuilderTest`) contiene «NOTA DE CRÉDITO», «Comprobante que se modifica», «FACTURA», `001-001-000000123`, `01/10/2026`, «Razón de modificación», el motivo, «Valor modificación» y la clave de acceso.
  - `NoteRideTest.debitNoteShowsReasonsAndPayment`: contiene «NOTA DE DÉBITO», cada razón con su valor, «Valor total» y el nombre de la forma de pago.
  - `NoteKindsTest`: `DocumentKinds` construido con los tres kinds encuentra `CREDIT_NOTE` y `DEBIT_NOTE`; `schema()`, `displayName()`, `fileStem()`, `auditReserved()` con los valores de Global Constraints; `buildXml` de cada uno es igual al builder de su tarea.
  - `RidePdfBuilderTest` pasa sin cambios.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar.** Extraer de `RidePdfBuilder` a `RideLayout` sin cambiar el PDF de la factura; `DebitNoteKind.buildRide` obtiene los nombres de forma de pago con `FiscalDocumentRepository.paymentMethodNames()`.
- [ ] **Step 4: Correr** `NoteRideTest`, `NoteKindsTest`, `RidePdfBuilderTest`, `DocumentKindsTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(notes): RIDE y DocumentKind de las notas`

### Task 4: `POST /credit-notes`

**Files:**
- Create: `notes/ModifiedInvoices.java`, `notes/CreditNoteService.java`, `notes/CreditNoteController.java`
- Modify: `billing/FiscalDocumentRepository.java` (lecturas de la factura modificada), `contracts/openapi.yaml`
- Test: `src/test/java/com/example/einvoice/notes/CreditNoteApiIT.java`; `src/test/java/com/example/einvoice/support/BillingScenario.java` (helpers de JSON de notas)

**Interfaces:**
- Consumes: `DocumentIssuer.issue(TenantAccess, String idempotencyKey, String requestHash, IssueRequest, Function<IssueContext, Preparation<C>>)`, `InvoiceController.IDEMPOTENCY_HEADER` y su forma de calcular el hash de la petición, `InvoiceCalculator.calculate(List<InvoiceLineInput>)`, `ProductService`, `CustomerService`, `CatalogRepository`, `CreditNoteKind`.
- Produces:
  - `ModifiedInvoices` (`@Component`): `ModifiedInvoice lockForNote(TenantAccess access, UUID invoiceId, UUID taxpayerId)` — bloquea la fila `FOR UPDATE`, aplica 404 / `MODIFIED_DOCUMENT_NOT_INVOICE` / `MODIFIED_INVOICE_NOT_AUTHORIZED` / `MODIFIED_INVOICE_OTHER_ISSUER`; `record ModifiedInvoice(UUID id, String number, LocalDate issueDate, BigDecimal total, UUID customerId, List<LineRow> lines)`.
  - `FiscalDocumentRepository.creditedTotal(UUID tenantId, UUID invoiceId): BigDecimal` — suma de `total` de las notas `'04'` vigentes con ese `modified_document_id` (0 si no hay); la usan el servicio (saldo) y la vista (tarea 6).
  - `POST /api/v1/credit-notes` → `202 {id, state, statusUrl}` como `/invoices`.

- [ ] **Step 1: Pruebas en `CreditNoteApiIT`** (con una factura autorizada: emitirla y pasarla a `AUTHORIZED` por SQL, como hacen otras IT):
  - `creditNoteFromInvoiceCopiesLinesAndBuyer`: una línea con `invoiceLineNo: 1`, cantidad `1` → 202; el sobre tiene el comprador de la factura, `modified_document_id` = factura, `modified_doc_number` = número de la factura; el `content` tiene `mainCode`, `description` y tarifa de la línea 1.
  - `lineAboveTheOriginalIsRejected`: cantidad mayor → 422 `CREDIT_LINE_EXCEEDS_INVOICE`; precio mayor → igual; `invoiceLineNo: 9` → 422 `CREDIT_LINE_UNKNOWN`.
  - `creditCannotExceedTheInvoice`: una nota por el total y otra por `0.01` → la segunda 422 `CREDIT_EXCEEDS_INVOICE`.
  - `rejectedNotesDoNotConsumeBalance`: la primera nota pasa a `NOT_AUTHORIZED` por SQL; otra por el total → 202.
  - `concurrentNotesNeverExceedTheInvoice`: dos peticiones en paralelo, cada una por el 60 % del total, con claves distintas → exactamente una 202 y una 422 `CREDIT_EXCEEDS_INVOICE`.
  - `invoiceMustBeAuthorizedSameIssuerAndOwn`: factura `RESERVED` → 422 `MODIFIED_INVOICE_NOT_AUTHORIZED`; de otro emisor de la empresa → 422 `MODIFIED_INVOICE_OTHER_ISSUER`; de otra empresa → 404; un documento `'04'` como `invoiceId` → 422 `MODIFIED_DOCUMENT_NOT_INVOICE`.
  - `manualCreditNoteUsesCatalogLines`: `modifiedDocument` + `customerId` + líneas con `productId` → 202, sin `modified_document_id`.
  - `modifiedDocumentIsRequiredOnce`: sin ninguno → 400 `MODIFIED_DOCUMENT_REQUIRED`; con `invoiceId` y `modifiedDocument` → 400; con `invoiceId` y `customerId` → 400.
  - `modifiedDocumentCannotBeLater`: fecha del documento modificado posterior a `issueDate` → 422 `INVALID_MODIFIED_DOCUMENT_DATE`.
  - `idempotentRetryReturnsTheSameNote`.
  - `invalidLineAmountsUseTheInvoiceRules`: descuento mayor que el subtotal de la línea → 422 `INVALID_LINE`.
- [ ] **Step 2: Correr** `-Dit.test=CreditNoteApiIT` — Expected: FAIL.
- [ ] **Step 3: Implementar.** Validación del cuerpo con Bean Validation como `InvoiceController` (`reason` `@NotBlank @Size(max = 300)` y sin controles; 1..200 líneas). `CreditNoteService.issue` arma `IssueRequest` y el `prepare`: con `invoiceId`, `ModifiedInvoices.lockForNote`, líneas desde las de la factura (precio y descuento por omisión, los originales), cálculo con `InvoiceCalculator`, saldo con `creditedTotal`; a mano, catálogo como en la factura. `Preparation` con el comprador (`CustomerService.get` del `customerId` de la factura o del cuerpo), `Totals(subtotal, totalDiscount, totalTax, total)`, `ModifiedDocument(invoiceId|null, "01", number, issueDate)` y el `CreditNoteContent`.
- [ ] **Step 4: Contrato:** `POST /credit-notes` con `IssueCreditNoteRequest` (sin `oneOf`: `invoiceId`, `modifiedDocument` y `customerId` opcionales, y la regla de cuál va escrita en la descripción), respuestas 202/400/404/409/422 como `/invoices`, los errores nuevos en las descripciones.
- [ ] **Step 5: Correr** `CreditNoteApiIT`, `OpenApiContractIT` — Expected: PASS.
- [ ] **Step 6: Commit** — `feat(notes): emisión de notas de crédito`

### Task 5: `POST /debit-notes`

**Files:**
- Create: `notes/DebitNoteService.java`, `notes/DebitNoteController.java`
- Modify: `contracts/openapi.yaml`
- Test: `src/test/java/com/example/einvoice/notes/DebitNoteApiIT.java`

**Interfaces:**
- Consumes: `ModifiedInvoices.lockForNote` (tarea 4, sin el saldo), `DebitNoteCalculator` (tarea 2), `CatalogRepository.ivaRate`, `CatalogRepository.paymentMethodValidOn`, el límite de consumidor final de `InvoiceService` (extraer la comprobación a un método reutilizable si hace falta).
- Produces: `POST /api/v1/debit-notes` → `202 {id, state, statusUrl}`.

- [ ] **Step 1: Pruebas en `DebitNoteApiIT`.**
  - `debitNoteFromInvoice`: dos motivos (15 % y 0 %), pago por el total → 202; content con dos motivos, impuestos por tarifa y el pago; comprador el de la factura.
  - `paymentMustMatchTotal` → 422 `PAYMENTS_MISMATCH`; forma de pago no vigente → 422 `PAYMENT_METHOD_UNKNOWN`; tarifa inexistente → 422 `TAX_RATE_UNKNOWN`.
  - `manualDebitNote`: `modifiedDocument` + `customerId` → 202.
  - `finalConsumerLimitApplies`: comprador consumidor final y total sobre el límite → 422 `FINAL_CONSUMER_LIMIT_EXCEEDED`.
  - `reasonsAndPaymentLimits`: 0 motivos o 21 → 400; 0 o 2 pagos → 400; razón con salto de línea → 400.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar** con el mismo esquema que la tarea 4 (sin saldo ni líneas de factura).
- [ ] **Step 4: Contrato:** `POST /debit-notes` con `IssueDebitNoteRequest`.
- [ ] **Step 5: Correr** `DebitNoteApiIT`, `OpenApiContractIT` — Expected: PASS.
- [ ] **Step 6: Commit** — `feat(notes): emisión de notas de débito`

### Task 6: Vista del comprobante, pipeline completo y documentación

**Files:**
- Modify: `billing/DocumentController.java`, `contracts/openapi.yaml`, `docs/01-product-requirements.md`, `docs/05-electronic-document-flow.md`, `docs/11-api-specification.md`, `docs/14-xsd-integration.md`
- Test: `src/test/java/com/example/einvoice/notes/NotesPipelineIT.java`, la IT de documentos (`InvoiceApiIT`) para la vista

**Interfaces:**
- Consumes: `DocumentKinds`, `CreditNoteKind`/`DebitNoteKind` (`loadContent`), `FiscalDocumentRepository.envelope` y `creditedTotal`.
- Produces: en `DocumentView` (contrato `Document`): `modifiedDocument {code, number, issueDate, documentId}` (nulo fuera de las notas), `reason` (nota de crédito), `reasons [{reason, value, taxRateCode, taxRate, taxAmount}]` (nota de débito), `creditedTotal` (solo facturas; cadena decimal). Las líneas de la nota de crédito salen en `lines` con `invoiceLineNo`; los pagos de la nota de débito en `payments`; sus impuestos en `taxes`.

- [ ] **Step 1: Pruebas.**
  - `NotesPipelineIT.creditNoteEndsAuthorizedWithRideAndMail` y `debitNoteEndsAuthorizedWithRideAndMail`: con el SRI simulado (como `PipelineEndToEndIT`), la nota llega a `AUTHORIZED`, existe su `RIDE_PDF` y el correo sale con asunto «Nota de crédito …» / «Nota de débito …» y adjunto `nota-credito-….pdf` / `nota-debito-….pdf`.
  - Vista: `creditNoteViewShowsModifiedDocumentReasonAndLines`, `debitNoteViewShowsReasonsAndPayments`, `invoiceViewShowsCreditedTotal` (`"0.00"` sin notas; tras una nota de crédito vigente, su total).
  - `OpenApiContractIT` pasa.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar** los campos de la vista (el contenido se lee con el kind del tipo) y el contrato.
- [ ] **Step 4: Docs:** 01 (las notas entran en el alcance), 05 (flujo de una nota: documento modificado, bloqueo y saldo), 11 (los dos endpoints, sus cuerpos y errores; campos nuevos de la vista), 14 (nota de crédito 1.1.0 y de débito 1.0.0 se emiten).
- [ ] **Step 5: Correr** `NotesPipelineIT`, `InvoiceApiIT`, `OpenApiContractIT`, y después `./mvnw -B verify` completo — Expected: BUILD SUCCESS.
- [ ] **Step 6: Commit** — `feat(notes): las notas en la vista del comprobante, RIDE y correo de punta a punta`

### Task 7: Web — contrato, listado y detalle por tipo

**Files:**
- Create (worktree): `git -C "D:\Proyectos Personales\client-factura-facil" worktree add -b p12c-notas-credito-debito "D:\Proyectos Personales\.worktrees\client-p12c-notas" origin/dev`
- Modify: `contracts/openapi.yaml` (copia del API), `src/api/schema.ts` (generado), `src/app/(app)/documentos/page.tsx`, `src/app/(app)/documentos/[id]/page.tsx`
- Create: `src/lib/documentTypes.ts`
- Test: `src/lib/documentTypes.test.ts`, `src/app/(app)/documentos/page.test.tsx`, `src/app/(app)/documentos/[id]/page.test.tsx` (crear si no existen, siguiendo el estilo de las pruebas de página existentes)

**Interfaces:**
- Produces: `documentTypeLabel(code: string): string` («Factura», «Nota de crédito», «Nota de débito», «Liquidación de compra», «Guía de remisión», «Comprobante de retención»; desconocido → «Comprobante») y `documentTypeShort(code)` («Factura», «N. crédito», «N. débito», …).

- [ ] **Step 1: Pruebas.**
  - `documentTypes.test.ts`: etiquetas de los seis códigos y el desconocido.
  - Listado: muestra la columna «Tipo» con «N. crédito» para un `04`; el selector «Tipo» con «Notas de crédito» pide `GET /documents` con `documentType=04` y vuelve a la página 0.
  - Detalle de una nota de crédito: título «Nota de crédito 001-001-000000004», «Modifica a: Factura 001-001-000000123 del 01/10/2026» con enlace a `/documentos/<documentId>`, el motivo y sus líneas; sin botón «Reemitir».
  - Detalle de una nota de débito: la tabla de motivos y la forma de pago.
  - Detalle de una factura `AUTHORIZED` (quien puede facturar): «Acreditado $X de $Y» y enlaces «Nota de crédito» → `/notas/nueva?tipo=credito&factura=<id>` y «Nota de débito» → `/notas/nueva?tipo=debito&factura=<id>`; sin ellos en una factura no autorizada o para quien no factura.
- [ ] **Step 2: Correr** `npm test` — Expected: FAIL.
- [ ] **Step 3: Implementar** (copiar el contrato, `npm run api:generate`, luego las pantallas).
- [ ] **Step 4: Correr** `npm run lint && npm run typecheck && npm test && npm run api:check` — Expected: todo pasa.
- [ ] **Step 5: Commit** — `feat: el tipo de comprobante en el listado y el detalle`

### Task 8: Web — formulario de nota de crédito

**Files:**
- Create: `src/app/(app)/notas/nueva/page.tsx`, `src/app/(app)/notas/nueva/CreditNoteForm.tsx`
- Modify: `src/components/AppShell.tsx` (entrada «Nueva nota» para quien factura, tras «Borradores»)
- Test: `src/app/(app)/notas/nueva/CreditNoteForm.test.tsx`, `src/components/AppShell.test.tsx`

**Interfaces:**
- Consumes: `POST /credit-notes`, `GET /documents/{id}` (líneas, total, `creditedTotal`, comprador), `CatalogPicker`, `previewInvoice`/`sumAmounts` de `src/lib/invoicePreview.ts`, la carga del emisor y puntos que usa `facturas/nueva/page.tsx` (extraerla a `src/lib/issuer.ts` si se comparte).
- Produces: página con pestañas «Nota de crédito» / «Nota de débito» (`?tipo=`), que monta `CreditNoteForm` o `DebitNoteForm` (tarea 9; hasta entonces la pestaña de débito puede mostrar un aviso).

- [ ] **Step 1: Pruebas en `CreditNoteForm.test.tsx`** (API simulada como en las pruebas de `facturas/nueva`):
  - `withInvoiceShowsItsLinesAndBalance`: con `factura=<id>`, muestra las líneas, el comprador fijo, «Saldo disponible $Y−X».
  - `capsQuantityAndPrice`: cantidad mayor que la original muestra el error en la línea y deshabilita «Emitir nota de crédito».
  - `noBalanceDisablesEmit`: con `creditedTotal` igual al total, «Saldo disponible $0.00» y el botón deshabilitado.
  - `emitsWithInvoiceLineNumbers`: marcar la línea 2, cantidad `1`, motivo → `POST /credit-notes` con `invoiceId`, `lines: [{ invoiceLineNo: 2, quantity: "1", … }]`, cabecera `Idempotency-Key`; navega a `/documentos/<id>`.
  - `manualModeAsksForInvoiceNumberAndCustomer`: sin `factura`, pide número (`EEE-PPP-SSSSSSSSS`), fecha y cliente, muestra el aviso «¿La factura se emitió aquí? Ábrela desde Comprobantes» y envía `modifiedDocument` + `customerId` + líneas con `productId`.
  - `showsApiProblem`: un 422 `CREDIT_EXCEEDS_INVOICE` se muestra con su detalle.
  - `AppShell.test.tsx`: «Nueva nota» aparece solo para quien puede facturar.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar.** La clave de idempotencia se genera al montar el formulario (`crypto.randomUUID()`) y se conserva en los reintentos.
- [ ] **Step 4: Correr** `npm run lint && npm run typecheck && npm test` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat: emitir notas de crédito`

### Task 9: Web — formulario de nota de débito

**Files:**
- Create: `src/app/(app)/notas/nueva/DebitNoteForm.tsx`
- Modify: `src/app/(app)/notas/nueva/page.tsx`
- Test: `src/app/(app)/notas/nueva/DebitNoteForm.test.tsx`

**Interfaces:**
- Consumes: `POST /debit-notes`, `GET /catalogs/tax-rates`, `GET /catalogs/payment-methods`, el modo con o sin factura de la tarea 8.

- [ ] **Step 1: Pruebas.**
  - `previewGroupsTaxByRate`: motivos `10.00` (15 %) y `3.00` (0 %) → subtotal `13.00`, IVA 15 % `1.50`, total `14.50`; el importe pagado se completa con el total.
  - `addAndRemoveReasons`: agregar hasta 20 filas; quitar una.
  - `emitsReasonsAndPayment`: envía `reasons: [{ reason, value, ivaRateCode }]` y `payments: [{ methodCode, amount }]` con `invoiceId` o con `modifiedDocument` + `customerId`.
  - `emitDisabledUntilComplete`: sin razón o sin valor, el botón «Emitir nota de débito» está deshabilitado.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Correr** `npm run lint && npm run typecheck && npm test && npm run api:check` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat: emitir notas de débito`

### Task 10: e2e — nota de crédito desde una factura

**Files:**
- Create: `e2e/tests/nota-credito.spec.ts` (API)
- Modify (solo local, revertir antes del commit): `e2e/support/env.ts` `PORTS.redis` `56379` → `57379`

**Interfaces:**
- Consumes: `seedCompany`, `seedCustomer`, `seedProduct`, `selectCatalog`, `listDocuments`, `waitForMail` de `e2e/support`; el flujo de `factura.spec.ts` para emitir y esperar `Autorizada`.

- [ ] **Step 1: Escribir** `nota-credito.spec.ts`: emitir una factura (como `factura.spec.ts`) hasta «Autorizada»; en su detalle, «Nota de crédito»; marcar la línea 1 con cantidad `1`, motivo «Devolución»; «Emitir nota de crédito»; el detalle de la nota llega a «Autorizada» y dice «Modifica a: Factura 001-001-000000001»; en Comprobantes, el filtro «Notas de crédito» muestra solo esa nota; la factura muestra «Acreditado».
- [ ] **Step 2: Correr** según `e2e/README.md`, con la web en `D:\Proyectos Personales\.worktrees\client-p12c-notas` (`E2E_FRONTEND_DIR`) y el cambio local del puerto de Redis — Expected: PASS. Si el entorno local no permite correrlo, dejarlo escrito y decirlo en el informe (el CI lo corre con la web de la misma rama).
- [ ] **Step 3: Revertir** `e2e/support/env.ts` y comprobar con `git diff --stat` que solo queda el spec.
- [ ] **Step 4: Commit** — `test(e2e): nota de crédito desde una factura autorizada`
