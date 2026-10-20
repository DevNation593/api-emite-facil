# Modelo genérico de comprobante — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la emisión, el worker, el RIDE, el correo y la descarga trabajen con cualquier tipo de comprobante a través de `DocumentKind`, con la factura como primera implementación y el XML validado durante la emisión.

**Architecture:** `fiscal_documents` guarda un sobre común (V18 añade `content jsonb` y el documento modificado, y permite totales nulos). Cada tipo es un `DocumentKind<C>` registrado en `DocumentKinds`; `DocumentIssuer` hace la parte común de la emisión y valida el XML antes de confirmar; `DocumentProcessor`, `DocumentArchiver`, `DeliveryService` y `ArtifactController` piden al tipo su XML, su RIDE y sus nombres.

**Tech Stack:** Java 21+, Spring Boot 4, JdbcClient, Jackson 3 (`tools.jackson.databind.json.JsonMapper`), PostgreSQL/Flyway, JUnit 5 + AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-18-modelo-generico-comprobantes-design.md`

## Global Constraints

- Rama `p12b-modelo-comprobantes` en `api-emite-facil`, cortada de `p12-xsd-comprobantes`; PR con base `dev`. Nunca push a `dev`/`qa`/`main`.
- Comentarios, mensajes de error y documentos en español; nombres de código en inglés, como el resto del repositorio.
- Maven en Windows: `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/tmp/jdksock ./mvnw ...`. Las `*IT` necesitan Docker Desktop encendido (no arrancarlo: preguntar a Stiwar).
- Migración nueva: `V18__generic_documents.sql`. Las migraciones existentes no se editan.
- El XML de la factura no cambia ni un byte (`InvoiceXmlBuilderTest` lo compara).
- `POST /invoices` no cambia por fuera; la auditoría de la factura sigue llamándose `INVOICE_RESERVED`; el outbox publica `DOCUMENT_RESERVED` para todos.
- Error nuevo: `422 DOCUMENT_XML_INVALID`, detalle truncado a 500 caracteres. Motivos nuevos de `XSD_INVALID`: `DOCUMENT_TYPE_NOT_SUPPORTED`, `CONTENT_UNREADABLE`.
- Un commit por tarea como mínimo, con la línea `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **El XML validado al emitir y el que genera el worker deben ser idénticos.** Las escalas de `BigDecimal` leídas de la base (`numeric(18,6)`, `numeric(14,2)`) difieren de las calculadas en memoria; si el generador dependiera de la escala, la validación previa aprobaría un XML distinto del que se firma. Lo prueba la tarea 6 (`issuedXmlEqualsTheWorkerXml`).
2. **La guía de remisión no tiene `fechaEmision`** en su XML: la regla de la clave de acceso no puede exigirla. La tarea 2 la prueba con la fecha tomada de la propia clave.
3. **Reemitir con un tipo distinto** (una nota de crédito que «reemplaza» una factura) no debe enlazarse: 409 `REPLACED_DOCUMENT_OTHER_TYPE`. Lo prueba la tarea 6.
4. **Eventos `INVOICE_RESERVED` en vuelo durante el despliegue** deben seguir yendo a la cola de emisión. Lo prueba la tarea 6 (`MessagingConfigTest`).
5. **Un fallo de la validación previa no deja rastro:** ni documento, ni contador avanzado, ni outbox, ni clave de idempotencia ocupada. Lo prueba la tarea 6.

---

### Task 1: Migración V18 y escritura del sobre

**Files:**
- Create: `src/main/resources/db/migration/V18__generic_documents.sql`
- Create: `src/test/java/com/example/einvoice/GenericDocumentsMigrationIT.java`
- Modify: `src/test/java/com/example/einvoice/support/TestData.java` (nuevo `document(...)` con tipo, total y content)
- Modify: `docs/04-database-design.md`

**Interfaces:**
- Produces: columnas `content jsonb`, `modified_document_id uuid`, `modified_doc_code varchar(2)`, `modified_doc_number varchar(17)`, `modified_doc_date date`; `subtotal`, `total_discount`, `total_tax`, `total` admiten nulo. `TestData.document(JdbcTemplate jdbc, UUID tenantId, UUID taxpayerId, UUID emissionPointId, UUID customerId, int sequence, String accessKey, String documentType, BigDecimal total, String contentJson)` devuelve el `UUID`.

- [ ] **Step 1: Escribir `GenericDocumentsMigrationIT` (extiende `AbstractIntegrationTest`, escenario como `BillingSchemaIT.Scenario`)** con estas pruebas, cada una con `assertThatThrownBy(...).isInstanceOf(DataIntegrityViolationException.class)` para el caso rechazado y una inserción que sí pasa:
  - `onlyKnownDocumentTypes`: tipo `'02'` rechazado; `'03'` con content `{}` aceptado.
  - `onlyRemissionGuideHasNoTotal`: `'06'` con total nulo aceptado; `'06'` con total `1` rechazado; `'04'` con total nulo rechazado.
  - `contentOnlyOutsideInvoices`: `'01'` con content `{}` rechazado; `'05'` sin content rechazado.
  - `modifiedDocumentIsCompleteOrAbsent`: los tres campos a la vez aceptado; solo `modified_doc_number` rechazado; `modified_document_id` sin los tres rechazado; `modified_doc_number = '1-1-1'` rechazado.
  - `modifiedDocumentStaysInTenant`: `modified_document_id` de una factura de otro tenant rechazado; de una del mismo tenant aceptado; el propio `id` rechazado.

- [ ] **Step 2: Correr y ver que falla**

Run: `./mvnw -B verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=GenericDocumentsMigrationIT -Djacoco.skip=true`
Expected: FAIL (columnas inexistentes / tipo `'02'` aceptado).

- [ ] **Step 3: Escribir V18** exactamente con el `ALTER TABLE` de la sección 2 del spec, más:
  - `CHECK ((document_type = '06') = (total IS NULL))`
  - `CHECK ((document_type = '01') = (content IS NULL))`
  - `CHECK ((modified_doc_code IS NULL) = (modified_doc_number IS NULL) AND (modified_doc_code IS NULL) = (modified_doc_date IS NULL))`
  - `CHECK (modified_document_id IS NULL OR modified_doc_code IS NOT NULL)`
  - `FOREIGN KEY (tenant_id, modified_document_id) REFERENCES fiscal_documents (tenant_id, id)`, `CHECK (modified_document_id <> id)`
  - `CREATE INDEX idx_fiscal_documents_modified ON fiscal_documents (modified_document_id) WHERE modified_document_id IS NOT NULL;`
  - Comentario en cabecera: `customer_*` es el receptor (comprador, proveedor, sujeto retenido o transportista); en la retención `total` es el total retenido.

- [ ] **Step 4: Correr `GenericDocumentsMigrationIT` y `BillingSchemaIT`** — Expected: PASS.

- [ ] **Step 5: Doc 04** — una viñeta «Comprobantes genéricos (`V18__generic_documents.sql`)» con las columnas, las restricciones y el significado de `customer_*`.

- [ ] **Step 6: Commit** — `feat(db): sobre común para todos los comprobantes`

### Task 2: Reglas del sobre para todos los tipos

**Files:**
- Modify: `src/main/java/com/example/einvoice/xml/DocumentType.java`
- Rename: `xml/InvoiceEnvelopeRules.java` → `xml/EnvelopeRules.java`; `src/test/.../xml/InvoiceEnvelopeRulesTest.java` → `EnvelopeRulesTest.java`
- Modify: `xml/EnvelopeViolation.java`, `orchestration/DocumentProcessor.java`, `src/test/.../orchestration/DocumentProcessingIT.java` (llamadas a la clase renombrada)

**Interfaces:**
- Produces: `DocumentType.rootElement()` (`factura`, `liquidacionCompra`, `notaCredito`, `notaDebito`, `guiaRemision`, `comprobanteRetencion`), `DocumentType.infoElement()` (`infoFactura`, `infoLiquidacionCompra`, `infoNotaCredito`, `infoNotaDebito`, `infoGuiaRemision`, `infoCompRetencion`); `EnvelopeRules.check(byte[] xml, DocumentSchema schema): Set<EnvelopeViolation>`; `EnvelopeViolation.ROOT_MISMATCH`.

- [ ] **Step 1: Escribir pruebas en `EnvelopeRulesTest`** (conservar las actuales cambiando la llamada a `EnvelopeRules.check(xml, DocumentSchema.INVOICE_2_1_0)`), y añadir:
  - `@ParameterizedTest @EnumSource(DocumentSchema.class) acceptsEachTypeWithItsCodeAndVersion`: el XML de ejemplo del esquema con `codDoc` reemplazado por `schema.type().code()`, `version="version0"` por `schema.version()`, sin el atributo `xsi:noNamespaceSchemaLocation`, y con una clave de acceso válida armada con `AccessKeyParts` a partir de los datos del propio ejemplo (para la guía, con cualquier fecha) → `check` devuelve vacío.
  - `rejectsAnotherTypesCode`: un ejemplo de nota de crédito con `codDoc` `01` contiene `COD_DOC_MISMATCH`.
  - `rejectsAnotherTypesRoot`: el ejemplo de nota de débito validado con `DocumentSchema.CREDIT_NOTE_1_1_0` contiene `ROOT_MISMATCH`.
  - `remissionGuideKeyIsCheckedWithoutIssueDate`: guía con clave válida cuyo RUC no coincide con el de `infoTributaria` → `ACCESS_KEY_MISMATCH`; con todo coincidente → sin violaciones.

- [ ] **Step 2: Correr** `./mvnw -B -q test -Dtest=EnvelopeRulesTest` — Expected: FAIL (no compila).

- [ ] **Step 3: Implementar.** `EnvelopeRules.check` lee el nombre del elemento raíz (`ROOT_MISMATCH` si no es `schema.type().rootElement()`), compara `codDoc` con `schema.type().code()` y `version` con `schema.version()`, y captura `fechaEmision` bajo `schema.type().infoElement()`. Si el tipo no tiene `fechaEmision` en el XML (la guía), la clave esperada se arma con las 8 primeras cifras de la clave declarada en lugar de la fecha.

- [ ] **Step 4: Correr** `EnvelopeRulesTest` e `InvoiceXmlBuilderTest` — Expected: PASS.

- [ ] **Step 5: Commit** — `feat(xml): reglas del sobre para todos los comprobantes`

### Task 3: `DocumentKind`, el sobre y la factura como primer tipo

**Files:**
- Create (paquete `com.example.einvoice.billing`): `DocumentKind.java`, `DocumentKinds.java`, `DocumentEnvelope.java`, `RideEnvelope.java`, `JsonContentKind.java`, `InvoiceBody.java`, `InvoiceKind.java`
- Modify: `billing/FiscalDocumentRepository.java`
- Test: `src/test/.../billing/DocumentKindsTest.java`, `src/test/.../billing/InvoiceKindTest.java`, `src/test/.../billing/JsonContentKindIT.java`

**Interfaces:**
- Consumes: `DocumentType`, `DocumentSchema` (paquete `xml`), `InvoiceXmlData`, `InvoiceXmlBuilder`, `RideData`, `RidePdfBuilder`.
- Produces:
  ```java
  public record DocumentEnvelope(DocumentType type, String environment, Issuer issuer, String accessKey,
  		String establishmentCode, String emissionPointCode, int sequence, LocalDate issueDate,
  		Recipient recipient, Totals totals, ModifiedDocument modified, String providerRuc) {
  	public record Issuer(String legalName, String tradeName, String ruc, String headOfficeAddress,
  			String establishmentAddress, boolean accountingRequired, String specialTaxpayerCode,
  			String withholdingAgentCode, String rimpeRegime) {}
  	public record Recipient(String idType, String idNumber, String name, String address, String email) {}
  	/** total no nulo; subtotal, totalDiscount y totalTax pueden serlo (retención). */
  	public record Totals(BigDecimal subtotal, BigDecimal totalDiscount, BigDecimal totalTax, BigDecimal total) {}
  	/** documentId nulo cuando el comprobante modificado se escribió a mano. */
  	public record ModifiedDocument(UUID documentId, String code, String number, LocalDate issueDate) {}
  	public String number() // "EEE-PPP-SSSSSSSSS"
  }
  public record RideEnvelope(DocumentEnvelope envelope, String authorizationNumber, OffsetDateTime authorizedAt) {}
  public interface DocumentKind<C> {
  	DocumentType type(); DocumentSchema schema(); String displayName(); String fileStem(); String auditReserved();
  	/** JSON para la columna content, que va en el mismo INSERT del sobre; nulo para la factura. */
  	String contentJson(C content);
  	/** Lo que el tipo guarda en tablas propias tras insertar el sobre; la factura, su cuerpo. */
  	default void saveDetails(UUID tenantId, UUID documentId, C content) {}
  	C loadContent(UUID tenantId, UUID documentId);
  	byte[] buildXml(DocumentEnvelope envelope, C content);
  	byte[] buildRide(RideEnvelope ride, C content);
  }
  public abstract class JsonContentKind<C> implements DocumentKind<C> // ctor (Class<C>, FiscalDocumentRepository, JsonMapper)
  public record InvoiceBody(List<NewLine> lines, List<TaxTotal> taxes, List<NewPayment> payments) {}
  @Component public class InvoiceKind implements DocumentKind<InvoiceBody>
  @Component public class DocumentKinds { public DocumentKinds(List<DocumentKind<?>> kinds); public Optional<DocumentKind<?>> find(DocumentType type); }
  ```
  `FiscalDocumentRepository`: `Optional<DocumentEnvelope> envelope(UUID tenantId, UUID documentId)`, `Optional<RideEnvelope> rideEnvelope(UUID tenantId, UUID documentId)` (solo autorizados, como hoy `rideData`), `Optional<String> content(UUID tenantId, UUID documentId)`, `void insertInvoiceBody(UUID tenantId, UUID documentId, InvoiceBody body)`, `InvoiceBody invoiceBody(UUID tenantId, UUID documentId)`, `Map<String, String> paymentMethodNames()`. `xmlData` y `rideData` desaparecen cuando la tarea 5 deja de usarlos; en esta tarea siguen.

- [ ] **Step 1: Pruebas unitarias.**
  - `DocumentKindsTest.findsTheInvoice`: `new DocumentKinds(List.of(invoiceKind)).find(DocumentType.INVOICE)` presente; `find(DocumentType.CREDIT_NOTE)` vacío.
  - `DocumentKindsTest.duplicateTypeFails`: dos kinds del mismo tipo → `IllegalStateException`.
  - `InvoiceKindTest.xmlIsTheSameAsTheInvoiceBuilder`: con el `InvoiceXmlData` de `InvoiceXmlFixtures`, convertido a `DocumentEnvelope` + `InvoiceBody`, `buildXml` es igual byte a byte a `InvoiceXmlBuilder.build(data)`.
  - `InvoiceKindTest.namesAndSchema`: `schema()` es `DocumentSchema.INVOICE_1_1_0`, `displayName()` es `"Factura"`, `fileStem()` es `"factura"`, `auditReserved()` es `"INVOICE_RESERVED"`.
- [ ] **Step 2: Prueba de integración `JsonContentKindIT.contentRoundTripKeepsScale`.** Un `JsonContentKind<Sample>` de prueba (record `Sample(String text, BigDecimal amount)`, tipo `PURCHASE_LIQUIDATION`): `contentJson(new Sample("a", new BigDecimal("1.50")))` contiene `"amount":"1.50"`; insertado con `TestData.document(..., "03", total, ese JSON)`, `loadContent` devuelve un `Sample` igual, con escala 2.
- [ ] **Step 3: Correr** — Expected: FAIL (no compila).
- [ ] **Step 4: Implementar.** `JsonContentKind` implementa `contentJson` y `loadContent` con `JsonMapper` y `FiscalDocumentRepository.content`; los importes del record salen como cadena (anotar los campos `BigDecimal` con `@JsonFormat(shape = STRING)` o configurar el mapper local con `JsonNodeFeature`/`DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`, lo que conserve la escala en la prueba). `InvoiceKind` convierte sobre + cuerpo en `InvoiceXmlData` y delega en `InvoiceXmlBuilder.build`; `buildRide` arma `RideData` (correo = `recipient().email()`, nombres de formas de pago = `paymentMethodNames()`) y llama `RidePdfBuilder.build`. `DocumentKinds` indexa en un `EnumMap`.
- [ ] **Step 5: Correr** las pruebas de los pasos 1 y 2 — Expected: PASS.
- [ ] **Step 6: Commit** — `feat(billing): DocumentKind y la factura como primer tipo`

### Task 4: El worker genera según el tipo

**Files:**
- Create: `billing/DocumentXmlCheck.java`
- Modify: `orchestration/DocumentProcessor.java`
- Test: `src/test/.../billing/DocumentXmlCheckTest.java`, `src/test/.../orchestration/DocumentProcessingIT.java`

**Interfaces:**
- Consumes: `DocumentKinds`, `DocumentKind`, `FiscalDocumentRepository.envelope`, `EnvelopeRules.check(byte[], DocumentSchema)`, `XsdValidator.validate(byte[], DocumentSchema)`.
- Produces: `@Component DocumentXmlCheck { Optional<String> problem(byte[] xml, DocumentSchema schema); }` — mensaje del tamaño (320 KB, el texto actual), errores del XSD unidos con `"; "`, o violaciones ordenadas unidas con `", "`; vacío si está bien.

- [ ] **Step 1: Pruebas.**
  - `DocumentXmlCheckTest`: el XML de `InvoiceXmlFixtures` válido → vacío; uno de 321 KB → mensaje que contiene `"320 KB"`; uno con `codDoc` `04` contra `INVOICE_1_1_0` → contiene `COD_DOC_MISMATCH`.
  - `DocumentProcessingIT.unsupportedTypeEndsInXsdInvalid`: documento `'04'` insertado con `TestData.document` (content `{}`, estado `RESERVED`), `generateAndSign` → `"XSD_INVALID"` y el último `state_events.reason` es `DOCUMENT_TYPE_NOT_SUPPORTED`.
  - `DocumentProcessingIT.unreadableContentEndsInXsdInvalid`: registrar en la prueba un `JsonContentKind` de tipo `'05'` y un documento `'05'` cuyo content es JSON válido pero no encaja en su record (`[]`) → `XSD_INVALID` con motivo `CONTENT_UNREADABLE`.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar.** `DocumentProcessor.generate`: lee `envelope`, busca el kind por `envelope.type()` (vacío → `fail(..., XSD_INVALID, "DOCUMENT_TYPE_NOT_SUPPORTED")`), carga el contenido (`RuntimeException` de Jackson → log de error + `fail(..., "CONTENT_UNREADABLE")`), genera con un método genérico `private <C> byte[] build(DocumentKind<C> kind, ...)`, valida con `DocumentXmlCheck`, guarda `XML_UNSIGNED` y `setSchemaVersion(kind.schema().version())`. La firma vuelve a validar con el esquema del tipo (leído del `schema_version` y `document_type` guardados). Quitar `problemWith` y las referencias a `InvoiceXmlBuilder.VERSION`.
- [ ] **Step 4: Correr** `DocumentXmlCheckTest`, `DocumentProcessingIT`, `PipelineEndToEndIT` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(orchestration): el worker genera el XML según el tipo`

### Task 5: Archivo, entrega y descarga según el tipo

**Files:**
- Modify: `delivery/DocumentArchiver.java`, `delivery/DeliveryService.java`, `delivery/DeliveryEmailFactory.java`, `storage/ArtifactController.java`, `storage/ArtifactStore.java` (si hace falta el tipo en `Download`, ya lo trae), `billing/FiscalDocumentRepository.java` (quitar `xmlData` y `rideData`)
- Test: `src/test/.../delivery/DeliveryEmailFactoryTest.java` (crear o ampliar), pruebas de entrega y descarga existentes

**Interfaces:**
- Consumes: `DocumentKinds`, `FiscalDocumentRepository.rideEnvelope`, `customerEmail`.
- Produces: `DeliveryEmailFactory.build(String from, RideEnvelope ride, String displayName, String fileStem, String recipient, byte[] ridePdf, byte[] authorizedXml): DeliveryEmail`.

- [ ] **Step 1: Pruebas.**
  - `DeliveryEmailFactoryTest.invoiceKeepsItsWording`: para una factura, asunto `"Factura 001-001-000000001 de <emisor>"`, el texto contiene `"le ha emitido la factura electrónica 001-001-000000001"` y el adjunto PDF se llama `factura-001-001-000000001.pdf` (igual que hoy).
  - `DeliveryEmailFactoryTest.otherTypeUsesItsName`: con `displayName` `"Nota de crédito"` y `fileStem` `"nota-credito"`, el asunto empieza por `"Nota de crédito "`, el texto dice `"le ha emitido la nota de crédito electrónica"` y el PDF es `nota-credito-….pdf`.
  - `DeliveryEmailFactoryTest.noTotalLineWithoutTotal`: con `totals` nulo el texto no contiene `"Valor total"`.
  - Las pruebas actuales de descarga siguen esperando `factura-<número>-<kind>.<ext>`.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar.** `DocumentArchiver` y `DeliveryService` piden `rideEnvelope` y el kind por tipo; el RIDE es `kind.buildRide(ride, kind.loadContent(...))`. El correo usa `displayName` (en minúsculas dentro de la frase) y `fileStem`. `ArtifactController` nombra con `kind.fileStem()` y deja `comprobante-` para un tipo sin kind. Borrar `xmlData` y `rideData` del repositorio.
- [ ] **Step 4: Correr** `DeliveryEmailFactoryTest` y las IT de `delivery` y `storage` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(delivery): RIDE, correo y descarga con el nombre de cada tipo`

### Task 6: `DocumentIssuer` y validación del XML al emitir

**Files:**
- Create: `billing/DocumentIssuer.java`
- Modify: `billing/InvoiceService.java`, `billing/FiscalDocumentRepository.java` (`NewDocument`), `orchestration/MessagingConfig.java` (solo si hiciera falta; la regla actual ya cubre `DOCUMENT_RESERVED`)
- Test: `src/test/.../billing/DocumentIssuerIT.java`, `src/test/.../orchestration/MessagingConfigTest.java`; ajustar pruebas que comprueben el `event_type` `INVOICE_RESERVED` del outbox

**Interfaces:**
- Consumes: todo lo anterior; `IdempotencyStore`, `SequenceService.reserve`, `AccessKeyParts`, `NumericCodeGenerator`, `OutboxWriter`, `AuditLog`, `TaxpayerService`, `CustomerService`.
- Produces:
  ```java
  @Service public class DocumentIssuer {
  	public record IssueRequest(UUID taxpayerId, String establishmentCode, String emissionPointCode,
  			LocalDate issueDate, String currency, UUID replacesDocumentId) {}
  	public record IssueContext(Taxpayer taxpayer, LocalDate issueDate) {}
  	public record Preparation<C>(DocumentKind<C> kind, Customer recipient, DocumentEnvelope.Totals totals,
  			DocumentEnvelope.ModifiedDocument modified, C content) {}
  	public <C> UUID issue(TenantAccess access, String idempotencyKey, String requestHash, IssueRequest request,
  			Function<IssueContext, Preparation<C>> prepare);
  }
  ```
  `NewDocument(UUID tenantId, UUID taxpayerId, UUID emissionPointId, UUID customerId, String state, UUID createdBy, DocumentEnvelope envelope, String contentJson)`; `FiscalDocumentRepository.insert(NewDocument)` inserta el sobre con los campos del documento modificado y `content` (`CAST(:content AS jsonb)`), y devuelve el id.

- [ ] **Step 1: Pruebas.**
  - `DocumentIssuerIT.invalidXmlIsRejectedWithoutTrace`: con un `DocumentKind` de prueba (`@TestConfiguration`, tipo `PURCHASE_LIQUIDATION`, `JsonContentKind<Sample>`, `buildXml` devuelve `<liquidacionCompra/>`), `issue(...)` lanza `ApiException` con estado 422 y código `DOCUMENT_XML_INVALID`; después `document_sequences.next_value` del punto sigue igual, no hay filas nuevas en `fiscal_documents` ni en `outbox_events`, y la misma clave de idempotencia con otro `requestHash` no responde conflicto.
  - `DocumentIssuerIT.issuedXmlEqualsTheWorkerXml`: con `@MockitoSpyBean DocumentXmlCheck`, emitir una factura por `POST /invoices` con cantidad `2` y precio `1.5`, capturar los bytes que recibió `problem` durante la emisión, procesar con `DocumentProcessor.generateAndSign`, y comprobar que el artefacto `XML_UNSIGNED` es igual a esos bytes.
  - `DocumentIssuerIT.replacementMustBeOfTheSameType`: un documento `'03'` en `XSD_INVALID` (insertado con `TestData`) no puede ser reemplazado por una factura: 409 `REPLACED_DOCUMENT_OTHER_TYPE`.
  - `DocumentIssuerIT.outboxEventIsDocumentReserved`: tras emitir una factura, el outbox tiene `DOCUMENT_RESERVED` y la auditoría `INVOICE_RESERVED`.
  - `MessagingConfigTest.legacyAndNewReservedEventsGoToIssue`: `routingKey("INVOICE_RESERVED")` y `routingKey("DOCUMENT_RESERVED")` son iguales a la clave de emisión.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar `DocumentIssuer.issue`** en el orden de la sección 1 del spec. Arma el `DocumentEnvelope` en memoria con el secuencial y la clave reales, llama `kind.buildXml`, pasa el XML por `DocumentXmlCheck`; si hay problema, `throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_XML_INVALID", detalle truncado a 500)` (la transacción de `issue` se revierte, y con ella la reserva y la idempotencia). `requireReplaceable` (movido desde `InvoiceService`) añade: tipo distinto → 409 `REPLACED_DOCUMENT_OTHER_TYPE` «El comprobante que se reemplaza es de otro tipo». Inserta el sobre con `kind.contentJson(content)`, llama `kind.saveDetails`, evento `RESERVED`, auditoría `kind.auditReserved()`, outbox `DOCUMENT_RESERVED`, enlace de reemisión, `schedule` y cierre de la idempotencia.
- [ ] **Step 4: Adelgazar `InvoiceService.issue`**: misma firma pública; arma `IssueRequest` y pasa un `prepare` que hace lo propio de la factura (productos, `InvoiceCalculator`, importes máximos, pagos, consumidor final) y devuelve `Preparation<InvoiceBody>`. `InvoiceKind.saveDetails` llama `insertInvoiceBody`; su `contentJson` devuelve nulo.
- [ ] **Step 5: Correr** `DocumentIssuerIT`, `MessagingConfigTest`, todo `billing` y `orchestration` — Expected: PASS.
- [ ] **Step 6: Commit** — `feat(billing): emisión común con el XML validado antes de confirmar`

### Task 7: Filtro por tipo, totales nulos, contrato y documentación

**Files:**
- Modify: `billing/DocumentController.java`, `billing/FiscalDocumentRepository.java` (`page`, `count` con tipo opcional), `contracts/openapi.yaml`
- Modify: `docs/05-electronic-document-flow.md`, `docs/11-api-specification.md`, `docs/14-xsd-integration.md`
- Test: la IT de la API de documentos (la que hoy prueba `GET /documents`) y `OpenApiContractIT`

**Interfaces:**
- Consumes: `DocumentType.of(String)`.
- Produces: `GET /api/v1/documents?documentType=04`; `FiscalDocumentRepository.page(UUID tenantId, DocumentType type, int page, int size)` y `count(UUID tenantId, DocumentType type)` con `type` nulo para todos.

- [ ] **Step 1: Pruebas.**
  - `listFiltersByDocumentType`: con una factura emitida y un `'04'` insertado con `TestData`, `?documentType=04` devuelve solo el segundo y `total` 1; sin filtro, los dos.
  - `unknownDocumentTypeIs400`: `?documentType=02` → 400.
  - `remissionGuideHasNullTotal`: un `'06'` insertado con total nulo aparece en la lista con `"total": null` y `GET /documents/{id}` responde con `subtotal`, `totalDiscount`, `totalTax` y `total` nulos.
  - `OpenApiContractIT` pasa con el contrato actualizado.
- [ ] **Step 2: Correr** — Expected: FAIL.
- [ ] **Step 3: Implementar.** Parámetro `@RequestParam(required = false) String documentType`; desconocido → `ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Tipo de comprobante desconocido")`. Un `Decimals.format` nulo-seguro en el controlador para totales. Contrato: parámetro `documentType` con `enum: ['01','03','04','05','06','07']`; `documentType` con ese enum en `DocumentSummary` y `DocumentView`; `total`, `subtotal`, `totalDiscount`, `totalTax` como `type: [string, 'null']`; `DOCUMENT_XML_INVALID` en la descripción del 422 de `POST /invoices`.
- [ ] **Step 4: Docs.** 05: la validación del XML ocurre en la emisión y el worker la repite; el outbox publica `DOCUMENT_RESERVED`. 11: filtro `documentType`, totales nulos de la guía, error `DOCUMENT_XML_INVALID`. 14: una línea en «Estado de activos» con la versión que emitirá cada tipo y que se habilita con su propio PR.
- [ ] **Step 5: Verificación completa** — `./mvnw -B verify` — Expected: `BUILD SUCCESS`, sin fallos.
- [ ] **Step 6: Commit** — `feat(api): filtro por tipo de comprobante y totales opcionales`; luego push de la rama y PR hacia `dev` con el resumen de las siete tareas.
