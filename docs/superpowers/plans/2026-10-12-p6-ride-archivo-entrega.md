# P6 — RIDE, archivo y entrega: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que un comprobante autorizado quede archivado con su XML autorizado y su RIDE en PDF, ambos descargables e inmodificables, y se entregue al receptor por correo con reintentos y trazabilidad.

**Architecture:** Al autorizarse un comprobante el pipeline deja un evento `DOCUMENT_AUTHORIZED`. Un worker de entrega lo archiva (`DocumentArchiver`: XML autorizado + RIDE como artefactos) y procesa sus entregas (`DeliveryService`). La entrega vive en su propia tabla y **no toca el estado fiscal**: el comprobante sigue `AUTHORIZED` aunque el correo falle. El envío SMTP se hace fuera de toda transacción, con el mismo patrón de reserva y plazo que el envío al SRI, y los reintentos se programan en la base.

**Tech Stack:** Java 21, Spring Boot 4.1.1, OpenPDF 3.0.5 (PDF y código de barras), `spring-boot-starter-mail` (Jakarta Mail), Spring AMQP, `JdbcClient`, Flyway, Testcontainers; en pruebas, un servidor SMTP simulado propio (`SmtpStub`).

**Spec:** [docs/05-electronic-document-flow.md](../../05-electronic-document-flow.md) (§ RIDE y entrega), [docs/09-error-handling.md](../../09-error-handling.md) (clase "Entrega"), [docs/11-api-specification.md](../../11-api-specification.md), [docs/04-database-design.md](../../04-database-design.md) (`deliveries`); ficha técnica off-line v2.34, numerales 4.7, 4.8, 9.19–9.21 y Anexo 2, leídos el 2026-10-12.

## Datos normativos (leídos de la fuente)

- **4.7 y 4.8.** El emisor está obligado a enviar el comprobante al receptor por correo electrónico; puede usar además otros medios. Se entrega una vez autorizado.
- **9.19.** El RIDE tiene validez tributaria; los anexos traen modelos con la posición de los requisitos, y se pueden imprimir datos adicionales.
- **9.20 y 9.21.** El código de barras con la clave de acceso es opcional; se recomienda GS1-128.
- **Anexo 2, notas del RIDE de factura.** La fecha y hora de autorización no es obligatoria en el RIDE del emisor. "El número de la clave de acceso corresponde al número de autorización." Se pueden mostrar solo los subtotales que tienen valor.
- **Modelo del Anexo 2.** Es una imagen (no texto). El RIDE de este plan sigue su disposición habitual: cabecera del emisor a la izquierda; a la derecha RUC, tipo y número, número y fecha de autorización, ambiente, emisión y clave de acceso con código de barras; datos del adquirente; detalle; información adicional y formas de pago; y cuadro de totales.

## Global Constraints

- El estado fiscal no cambia por la entrega. Un fallo de correo nunca toca `fiscal_documents.state`.
- Ningún envío SMTP dentro de una transacción ni con una fila bloqueada.
- Por defecto no se envía ningún correo: `einvoice.delivery.email.enabled=false`. Con `false` no se contacta ningún servidor SMTP.
- Los artefactos son de solo inserción y la base lo impone (ni `UPDATE`, ni `DELETE`, ni `TRUNCATE`).
- Solo se archivan y entregan comprobantes en `AUTHORIZED`.
- El correo sale únicamente a la dirección congelada en el comprobante o a la que indique un usuario con rol `OWNER`, `ADMIN` o `BILLER` de esa empresa. Documento ajeno = 404.
- Nada de datos del cliente, XML ni PDF en mensajes de RabbitMQ, `outbox` o logs. El contenido del correo no se guarda en la base.
- Dependencias nuevas de producción: solo `com.github.librepdf:openpdf:3.0.5` y `spring-boot-starter-mail`.
- Mensajes y comentarios en español; nombres de código en inglés; tabulación como el resto del código.
- Toda ejecución de Maven pasa por `.superpowers/sdd/2026-10-12-p6-ride-archivo-entrega/verify.sh <args>`.

## Decisiones de diseño

- **Fuera de este plan: webhooks.** El plan maestro los incluía aquí. Son un subsistema independiente (URL que aporta el cliente, protección SSRF, secretos, firma HMAC, API de suscripciones) y van en un plan propio.
- **`DELIVERY_PENDING`, `DELIVERED` y `DELIVERY_FAILED` del doc 05 no son estados del comprobante.** La entrega tiene su estado en `deliveries`: `PENDING`, `SENDING`, `SENT`, `FAILED`.
- **XML que se entrega:** el "XML autorizado" (`<autorizacion>` con estado, número, fecha, ambiente y el comprobante firmado en CDATA), construido con los datos guardados y el `XML_SIGNED` propio. La respuesta cruda del SRI sigue aparte como evidencia.
- **Entrega al menos una vez.** Si el proceso muere entre que el servidor SMTP acepta el mensaje y se anota `SENT`, el correo se repite al vencer el plazo.
- **Reintentos de correo:** espera `min(1 min × 2^(n−1), 6 h)` con ±20 %, 8 intentos (algo menos de un día); después `FAILED`. Un rechazo permanente del servidor (respuesta 5xx) es `FAILED` de inmediato.
- **Tope de entregas por comprobante:** 10, para que el reenvío manual no sirva para inundar una dirección.
- **Colas:** `document.deliver` y `document.deliver.dlq`. El relevo encamina por tipo de evento: `DOCUMENT_AUTHORIZED` y los `DELIVERY_*` a `document.deliver`; el resto a `document.issue`.
- **Red de seguridad del archivo:** un comprobante `AUTHORIZED` queda programado (`next_attempt_at`) hasta que está archivado; si su evento se pierde, el pipeline vuelve a emitir `DOCUMENT_AUTHORIZED`.

## Review Focus

1. **Dos workers procesan la misma entrega a la vez** → un solo correo (Task 4, `concurrentProcessingSendsOneEmail`).
2. **SMTP caído durante horas** → reintentos con espera creciente y, agotados, `FAILED`; el comprobante sigue `AUTHORIZED` y descargable (Task 4, `DeliveryRetryIT`).
3. **Nombres con saltos de línea o caracteres raros** en emisor o cliente → ni cabeceras inyectadas en el correo ni excepción al generar el PDF (Task 2 `oddCharactersNeverBreakTheRide`, Task 4 `namesCannotInjectHeaders`).
4. **Cliente sin correo, o correo desactivado** → se archiva igual, no se crea entrega y no se contacta SMTP (Task 4).
5. **Alguien intenta modificar o borrar un artefacto** directamente en la base → la base lo rechaza (Task 1).

---

### Task 1: Esquema V6, tipos de artefacto y XML autorizado

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__archive_delivery.sql`
- Modify: `backend/src/main/java/com/example/einvoice/storage/ArtifactKind.java` (`XML_AUTHORIZED("xml-authorized")`, `RIDE_PDF("ride")`, y `mediaType()`), `storage/ArtifactController.java` (tipo de contenido según el artefacto)
- Create: `backend/src/main/java/com/example/einvoice/delivery/AuthorizedXmlBuilder.java`
- Modify: `backend/src/test/java/com/example/einvoice/orchestration/DocumentProcessingIT.java` (la prueba que daña un artefacto debe desactivar el disparador para simular la corrupción)
- Test: `backend/src/test/java/com/example/einvoice/delivery/AuthorizedXmlBuilderTest.java`, `backend/src/test/java/com/example/einvoice/storage/ArtifactImmutabilityIT.java`

**Interfaces:**
- Produces:
  - `ArtifactKind.mediaType() : MediaType` — `application/pdf` para `RIDE_PDF`, `application/xml` para el resto.
  - `AuthorizedXmlBuilder.build(String authorizationNumber, OffsetDateTime authorizedAt, SriEnvironment environment, byte[] signedXml) : byte[]` — UTF-8; `estado` `AUTORIZADO`; `fechaAutorizacion` en hora de Ecuador (`-05:00`) con formato ISO; `ambiente` `PRUEBAS` o `PRODUCCIÓN`; el comprobante en CDATA (partiendo la sección si contiene `]]>`); `<mensajes/>` vacío.
  - Tabla `deliveries` y disparador de inmutabilidad (abajo).

```sql
ALTER TABLE document_artifacts DROP CONSTRAINT document_artifacts_kind_check;
ALTER TABLE document_artifacts ADD CONSTRAINT document_artifacts_kind_check
 CHECK (kind IN ('XML_UNSIGNED','XML_SIGNED','SRI_AUTHORIZATION','XML_AUTHORIZED','RIDE_PDF'));

CREATE FUNCTION reject_artifact_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'document_artifacts es de solo inserción' USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER document_artifacts_immutable BEFORE UPDATE OR DELETE ON document_artifacts
 FOR EACH ROW EXECUTE FUNCTION reject_artifact_change();
CREATE TRIGGER document_artifacts_no_truncate BEFORE TRUNCATE ON document_artifacts
 FOR EACH STATEMENT EXECUTE FUNCTION reject_artifact_change();

CREATE TABLE deliveries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 channel varchar(20) NOT NULL CHECK (channel IN ('EMAIL')),
 recipient text NOT NULL,
 status varchar(20) NOT NULL CHECK (status IN ('PENDING','SENDING','SENT','FAILED')),
 attempt_count integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz,          -- cuándo debe retomarse; nulo si no hay nada programado
 sending_since timestamptz,            -- inicio del envío en curso
 last_error text,
 provider_message_id text,
 requested_by uuid REFERENCES users(id), -- nulo: entrega automática al autorizarse
 created_at timestamptz NOT NULL DEFAULT now(),
 sent_at timestamptz,
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id)
);
CREATE INDEX idx_deliveries_document ON deliveries (document_id, created_at);
CREATE INDEX idx_deliveries_due ON deliveries (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE UNIQUE INDEX uq_deliveries_automatic ON deliveries (document_id, channel) WHERE requested_by IS NULL;
```

- [ ] **Step 1: Escribir las pruebas.**
  - `AuthorizedXmlBuilderTest`: `wrapsTheSignedInvoiceUntouched` (el resultado se lee con `SecureXml`; raíz `autorizacion`; `estado` `AUTORIZADO`; `numeroAutorizacion` el dado; `fechaAutorizacion` `2026-10-12T09:30:00-05:00` para el instante `2026-10-12T14:30:00Z`; `ambiente` `PRUEBAS`; el texto de `comprobante` es idéntico, carácter a carácter, al XML firmado, incluida su declaración `<?xml`); `productionLabel` (`PRODUCCIÓN`); `cdataTerminatorInsideTheInvoiceIsPreserved` (un XML que contiene `]]>` se recupera idéntico); `rejectsMissingData` (número nulo o fecha nula → `IllegalArgumentException`).
  - `ArtifactImmutabilityIT`: `artifactsCannotBeUpdatedDeletedOrTruncated` (tras generar un XML, `UPDATE`, `DELETE` y `TRUNCATE` sobre `document_artifacts` lanzan `DataIntegrityViolationException` y el contenido sigue igual); `newKindsAreAccepted` (`RIDE_PDF` y `XML_AUTHORIZED` se insertan; un tipo desconocido no).
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest=AuthorizedXmlBuilderTest -Dit.test=ArtifactImmutabilityIT` — Expected: error de compilación.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`.
- [ ] **Step 5: Commit** `feat(storage): artefactos inmutables, tipos de archivo y XML autorizado`

### Task 2: RIDE en PDF

**Files:**
- Modify: `backend/pom.xml` (`com.github.librepdf:openpdf:3.0.5`)
- Create: `backend/src/main/java/com/example/einvoice/delivery/{RideData,RidePdfBuilder}.java`
- Modify: `backend/src/main/java/com/example/einvoice/billing/FiscalDocumentRepository.java` (`rideData`), `catalog/CatalogRepository.java` (nombres de formas de pago, si no existe ya la consulta)
- Test: `backend/src/test/java/com/example/einvoice/delivery/RidePdfBuilderTest.java`, `backend/src/test/java/com/example/einvoice/delivery/RideDataIT.java`

**Interfaces:**
- Consumes: `InvoiceXmlData` (datos congelados del comprobante), `FiscalDocumentRepository.xmlData`.
- Produces:
  - `record RideData(InvoiceXmlData invoice, String authorizationNumber, OffsetDateTime authorizedAt, String customerEmail, Map<String, String> paymentMethodNames)`
  - `FiscalDocumentRepository.rideData(UUID tenantId, UUID documentId) : Optional<RideData>` — vacío si el documento no existe o no tiene autorización.
  - `RidePdfBuilder.build(RideData data) : byte[]` — A4 vertical, Helvetica, una o más páginas. `IllegalArgumentException` si falta el número de autorización.

Contenido obligatorio del RIDE (textos exactos de las etiquetas): `R.U.C.:`, `FACTURA`, `No.` + `EEE-PPP-SSSSSSSSS`, `NÚMERO DE AUTORIZACIÓN`, `FECHA Y HORA DE AUTORIZACIÓN` (dd/MM/yyyy HH:mm:ss, hora de Ecuador), `AMBIENTE:` (`PRUEBAS`/`PRODUCCIÓN`), `EMISIÓN:` `NORMAL`, `CLAVE DE ACCESO` con código de barras Code 128 y los 49 dígitos; razón social, nombre comercial, `Dirección Matriz:`, `Dirección Sucursal:`, `Contribuyente Especial Nro:` (si lo hay), `OBLIGADO A LLEVAR CONTABILIDAD:` `SI`/`NO`, `Agente de Retención Resolución No.` (si lo hay), la leyenda RIMPE (si la hay); `Razón Social / Nombres y Apellidos:`, `Identificación:`, `Fecha Emisión:` (dd/MM/yyyy), `Dirección:`; tabla de detalle con `Cod. Principal`, `Cantidad`, `Descripción`, `Precio Unitario`, `Descuento`, `Precio Total`; `Información Adicional` con el correo del cliente y el `RUC Proveedor` si existe; `Forma de pago` con el nombre del catálogo y su valor; totales `SUBTOTAL <tarifa>%` por cada tarifa con valor (los códigos 6 y 7 como `SUBTOTAL No objeto de IVA` y `SUBTOTAL Exento de IVA`), `SUBTOTAL SIN IMPUESTOS`, `TOTAL Descuento`, `IVA <tarifa>%` y `VALOR TOTAL`.

- [ ] **Step 1: Escribir las pruebas.**
  - `RidePdfBuilderTest` (extrae el texto del PDF con el extractor de OpenPDF y normaliza los espacios): `containsEveryRequiredField` (factura de ejemplo de `InvoiceXmlFixtures` con autorización: aparecen cada etiqueta de arriba y sus valores — RUC, número `001-001-000000001`, clave de acceso, número de autorización, `PRUEBAS`, cliente, fecha, descripción, cantidad, precio, `SUBTOTAL 15%`, `IVA 15%`, `VALOR TOTAL` e importe, nombre de la forma de pago); `isAValidPdfWithABarcode` (empieza por `%PDF-`, se abre con `PdfReader`, una página); `optionalLegendsAppearOnlyWhenPresent` (contribuyente especial, agente de retención, RIMPE, RUC proveedor: presentes con datos, ausentes sin ellos); `zeroRatedAndExemptSubtotalsAreLabelled`; `manyLinesFlowOntoMorePages` (300 líneas → más de una página y la última descripción presente); `oddCharactersNeverBreakTheRide` (nombres con `ñ á & < > "`, un salto de línea, un emoji y texto de 300 caracteres sin espacios → se genera y contiene los caracteres latinos); `unauthorizedDataIsRejected`.
  - `RideDataIT`: `authorizedInvoiceHasRideData` (tras `advance` → datos con número y fecha de autorización, correo del cliente y el nombre de la forma de pago `20`); `unauthorizedOrForeignDocumentHasNone`.
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest=RidePdfBuilderTest -Dit.test=RideDataIT` — Expected: error de compilación.
- [ ] **Step 3: Implementar.** Tablas de OpenPDF (`PdfPTable`) para cabecera, detalle y totales; `Barcode128` para la clave. El texto pasa por un saneador que quita caracteres de control y los que Helvetica (CP1252) no puede representar.
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`.
- [ ] **Step 5: Commit** `feat(delivery): RIDE de la factura en PDF`

### Task 3: Archivo del comprobante autorizado

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/delivery/DocumentArchiver.java`
- Modify: `backend/src/main/java/com/example/einvoice/orchestration/DocumentPipeline.java` (evento `DOCUMENT_AUTHORIZED` al autorizar; `AUTHORIZED` sin archivar queda programado y vuelve a emitir el evento)
- Modify: `backend/src/test/java/com/example/einvoice/orchestration/AuthorizationPipelineIT.java` (el comprobante autorizado queda programado hasta archivarse)
- Test: `backend/src/test/java/com/example/einvoice/delivery/DocumentArchiverIT.java`

**Interfaces:**
- Consumes: Task 1 (`AuthorizedXmlBuilder`, tipos de artefacto), Task 2 (`rideData`, `RidePdfBuilder`), `ArtifactStore.save/find`, `FiscalDocumentRepository.lockForProcessing/schedule`, `OutboxWriter.write`.
- Produces:
  - `DocumentArchiver.archive(UUID tenantId, UUID documentId) : boolean` — `@Transactional`; bloquea el documento; si no está `AUTHORIZED` devuelve `false` sin hacer nada; crea los artefactos `XML_AUTHORIZED` y `RIDE_PDF` que falten, deja el documento sin programar (`schedule(null, 0)`) y devuelve `true`. Llamarlo otra vez no cambia nada.
  - Evento del outbox `DOCUMENT_AUTHORIZED` (constante `DocumentPipeline.DOCUMENT_AUTHORIZED`) con `aggregate_id` = documento.
  - `DocumentPipeline.advance` sobre `AUTHORIZED`: si falta `RIDE_PDF`, escribe `DOCUMENT_AUTHORIZED` y programa el documento a `redelivery-lease`; si ya está archivado, lo deja sin programar.

- [ ] **Step 1: Escribir las pruebas** (`DocumentArchiverIT`):
  - `authorizedInvoiceIsArchivedWithXmlAndRide`: tras `advance` y `archive` → `true`; existen `XML_AUTHORIZED` y `RIDE_PDF`; el comprobante extraído del CDATA del XML autorizado es byte a byte el `XML_SIGNED` y **su firma sigue verificando** con `XadesVerifier`; el PDF contiene la clave de acceso; `next_attempt_at` nulo.
  - `archivingTwiceChangesNothing` (mismos SHA-256, cuatro... los mismos artefactos); `concurrentArchivingStoresEachArtifactOnce` (4 hilos).
  - `onlyAuthorizedDocumentsAreArchived` (`RESERVED`, `RECEIVED`, `NOT_AUTHORIZED` → `false`, sin artefactos nuevos); `foreignDocumentIsNotFound`.
  - `authorizationLeavesAnEventAndASafetyNet`: tras `advance` hasta `AUTHORIZED` hay un evento `DOCUMENT_AUTHORIZED` sin despachar y `next_attempt_at` futuro; un nuevo `advance` sin archivar escribe otro evento; tras `archive`, `advance` no escribe más y `next_attempt_at` queda nulo.
  - `artifactsAreDownloadableWithTheirContentType`: `GET …/artifacts/ride` → 200 `application/pdf`; `…/artifacts/xml-authorized` → 200 `application/xml`; ambos con `ETag`; antes de archivar, 404; documento ajeno, 404.
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=DocumentArchiverIT` — Expected: error de compilación.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`.
- [ ] **Step 5: Commit** `feat(delivery): archivo del comprobante autorizado`

### Task 4: Entrega por correo con reintentos

**Files:**
- Modify: `backend/pom.xml` (`spring-boot-starter-mail`), `backend/src/main/resources/application.yml` (`einvoice.delivery`, tiempos de espera SMTP)
- Create: `backend/src/main/java/com/example/einvoice/delivery/{DeliveryProperties,DeliveryEmail,DeliveryEmailFactory,DeliveryMailer,SmtpDeliveryMailer,DeliveryRepository,DeliveryService}.java`
- Modify: `backend/src/main/java/com/example/einvoice/orchestration/RetryPolicy.java` (pasa a público)
- Test: `backend/src/test/java/com/example/einvoice/support/SmtpStub.java`; `backend/src/test/java/com/example/einvoice/delivery/{DeliveryEmailFactoryTest,DeliveryIT,DeliveryRetryIT}.java`
- Modify: `backend/src/test/java/com/example/einvoice/support/AbstractIntegrationTest.java` (SMTP simulado, correo habilitado, esperas mínimas)

**Interfaces:**
- Consumes: `DocumentArchiver.archive`, `ArtifactStore.find`, `RetryPolicy.delay`.
- Produces:
  - `@ConfigurationProperties("einvoice.delivery") record DeliveryProperties(Email email, Duration retryBackoff, Duration retryMaxBackoff, int retryMaxAttempts, Duration sendLease, int maxPerDocument)` con `record Email(boolean enabled, String from)`. Por defecto: `enabled=${DELIVERY_EMAIL_ENABLED:false}`, `from=${DELIVERY_EMAIL_FROM:}`, `1m`, `6h`, `8`, `2m`, `10`. Con `enabled=true` y `from` vacío o inválido, o sin servidor SMTP configurado (`spring.mail.host`), la aplicación no arranca y el mensaje nombra la propiedad.
  - `record DeliveryEmail(String from, String fromName, String to, String subject, String text, List<Attachment> attachments)` con `record Attachment(String filename, String contentType, byte[] content)`.
  - `DeliveryEmailFactory.build(String from, RideData data, String recipient, byte[] ridePdf, byte[] authorizedXml) : DeliveryEmail` — asunto `Factura <número> de <nombre comercial o razón social>`; adjuntos `factura-<número>.pdf` (`application/pdf`) y `<claveAcceso>.xml` (`application/xml`); el texto incluye emisor, número, fecha, total, clave de acceso y número de autorización. Quita caracteres de control de todo lo que va a cabeceras.
  - `interface DeliveryMailer { Result send(DeliveryEmail email); record Result(boolean sent, String messageId, boolean permanent, String error) }` — nunca lanza. `SmtpDeliveryMailer` solo existe con el correo habilitado.
  - `DeliveryRepository`: `ensureAutomatic(tenantId, documentId, recipient) : boolean`, `create(tenantId, documentId, recipient, requestedBy) : UUID`, `claim(deliveryId, Duration lease) : boolean`, `markSent`, `markRetry`, `markFailed`, `pending(tenantId, documentId) : List<UUID>`, `list(tenantId, documentId) : List<DeliveryRow>`, `count(documentId) : int`.
  - `DeliveryService.process(UUID tenantId, UUID documentId) : int` — archiva; si el correo está habilitado y el comprobante tiene correo de cliente, asegura la entrega automática; intenta cada entrega pendiente o con envío vencido; devuelve cuántos correos envió. No es `@Transactional`.

Reserva de una entrega: `UPDATE deliveries SET status='SENDING', sending_since=now(), attempt_count=attempt_count+1, next_attempt_at=now()+lease WHERE id=:id AND (status='PENDING' OR (status='SENDING' AND sending_since < now()-lease))`. Resultado: enviado → `SENT`, `sent_at`, `provider_message_id`, sin programar; fallo permanente o `attempt_count >= retryMaxAttempts` → `FAILED`, sin programar, `last_error`; otro fallo → `PENDING`, `next_attempt_at = now() + RetryPolicy.delay(attempt_count, …)`, `last_error`. `last_error` se recorta a 500 caracteres y nunca incluye el contenido del mensaje.

`SmtpStub` (único por JVM, como `SriStub`): `host()`, `port()`, guiones por destinatario `onRecipient(email, Reply...)` con `Reply.accept()`, `Reply.rejectRecipient(550, "…")`, `Reply.failData(451, "…")`, `Reply.dropConnection()`; `messages(email) : List<MimeMessage>`; `connections()`.

- [ ] **Step 1: Escribir las pruebas.**
  - `DeliveryEmailFactoryTest`: `subjectTextAndAttachments`; `namesCannotInjectHeaders` (nombre comercial `Andina\r\nBcc: x@evil.test` → el asunto no contiene saltos de línea).
  - `DeliveryIT`: `authorizedInvoiceIsEmailedWithRideAndXml` (cliente con correo; `advance` y `process` → 1; el SMTP simulado recibió un mensaje a esa dirección con el `From` configurado, asunto con el número, y dos adjuntos cuyos bytes son los artefactos `RIDE_PDF` y `XML_AUTHORIZED`; entrega `SENT` con `sent_at` y `provider_message_id`; el comprobante sigue `AUTHORIZED`); `processingAgainSendsNothing`; `concurrentProcessingSendsOneEmail` (4 hilos, SMTP con retraso); `customerWithoutEmailIsArchivedButNotEmailed` (sin entrega, cero conexiones nuevas, artefactos presentes); `unauthorizedDocumentIsNeitherArchivedNorEmailed`; `foreignDocumentIsNotFound`.
  - `DeliveryRetryIT`: `temporaryFailureIsRetriedLater` (`451` en DATA → `PENDING`, `attempt_count` 1, `next_attempt_at` futuro, `last_error` con `451`; con el SMTP ya sano, `process` → `SENT` y un solo mensaje); `droppedConnectionIsRetried`; `rejectedRecipientFailsForGood` (`550` → `FAILED`, sin programar, un solo intento); `retriesAreExhausted` (`attempt_count` puesto a 7 por SQL y otro fallo temporal → `FAILED`); `abandonedSendIsTakenOverAfterTheLease` (entrega `SENDING` con `sending_since` de hace 10 minutos → se reenvía; con `sending_since` reciente → no se toca); `deliveryFailureNeverChangesTheFiscalState` (estado, versión y artefactos del comprobante idénticos antes y después).
  - Una prueba de arranque sin Spring completo (`DeliveryPropertiesTest`): `enabledWithoutSenderIsRejected`, `disabledNeedsNothing`.
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest='DeliveryEmailFactoryTest,DeliveryPropertiesTest' -Dit.test='DeliveryIT,DeliveryRetryIT'` — Expected: error de compilación.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`.
- [ ] **Step 5: Commit** `feat(delivery): entrega del comprobante por correo con reintentos`

### Task 5: Mensajería de la entrega

**Files:**
- Modify: `backend/src/main/java/com/example/einvoice/orchestration/{MessagingConfig,OutboxRelay,WorkerSchedule}.java`
- Create: `backend/src/main/java/com/example/einvoice/delivery/{DeliveryWorker,DueDeliveryScheduler}.java`
- Test: `backend/src/test/java/com/example/einvoice/delivery/{DeliveryRoutingIT,DueDeliverySchedulerIT}.java`; ampliar `orchestration/PipelineEndToEndIT.java`

**Interfaces:**
- Consumes: `DeliveryService.process`.
- Produces:
  - Cola `document.deliver` (clave de enrutamiento igual) con DLQ `document.deliver.dlq` por `fiscal.dlx`.
  - `OutboxRelay`: `DOCUMENT_AUTHORIZED` y todo tipo que empiece por `DELIVERY_` van a `document.deliver`; el resto a `document.issue`.
  - `DeliveryWorker.handle(Message)` — `@RabbitListener(queues = "document.deliver")`, solo con `einvoice.worker.enabled=true`; llama a `DeliveryService.process`; documento inexistente se descarta.
  - `DueDeliveryScheduler.enqueueDue() : int` — entregas con `next_attempt_at <= now()`: un evento `DELIVERY_DUE` por documento y `next_attempt_at = now() + redelivery-lease`. `WorkerSchedule.tick` lo llama.

- [ ] **Step 1: Escribir las pruebas.**
  - `DeliveryRoutingIT`: `eventsAreRoutedByType` (un `DOCUMENT_AUTHORIZED` y un `DELIVERY_DUE` acaban en `document.deliver`, un `INVOICE_RESERVED` en `document.issue`, ninguno en la otra cola); `rejectedDeliveryMessageGoesToItsDeadLetterQueue`.
  - `DueDeliverySchedulerIT`: `dueDeliveryGetsAnEventAndALease`; `sentFailedAndFutureDeliveriesAreIgnored`.
  - `PipelineEndToEndIT`: `issuedInvoiceIsEmailedWithoutAnyManualStep` (cliente con un correo único → con Awaitility, el SMTP simulado recibe un mensaje con dos adjuntos y `GET …/artifacts/ride` responde 200); `emailIsRetriedByTheSchedulerAfterATemporaryFailure` (primer intento `451`, después acepta → llega un solo mensaje).
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='DeliveryRoutingIT,DueDeliverySchedulerIT,PipelineEndToEndIT'` — Expected: error de compilación.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`.
- [ ] **Step 5: Commit** `feat(delivery): cola de entrega, worker y reintentos programados`

### Task 6: API de entregas

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/delivery/DeliveryController.java`
- Modify: `contracts/openapi.yaml` (versión 0.6.0)
- Test: `backend/src/test/java/com/example/einvoice/delivery/DeliveryApiIT.java`

**Interfaces:**
- Consumes: `DeliveryRepository.list/create/count`, `OutboxWriter.write`, `AuditLog.record`, `TenantAccess.requireBilling()`.
- Produces:
  - `GET /api/v1/documents/{id}/deliveries` → 200, la más reciente primero: `{ id, channel, recipient, status, attemptCount, lastError, createdAt, sentAt, automatic }`.
  - `POST /api/v1/documents/{id}/deliveries` con cuerpo opcional `{ "email": "…" }` → 202 con la entrega creada (`PENDING`). Roles `OWNER`, `ADMIN`, `BILLER`. Sin `email` usa el correo congelado en el comprobante. Errores: `409 DOCUMENT_NOT_AUTHORIZED`; `409 DELIVERY_DISABLED` (correo desactivado); `409 DELIVERY_LIMIT_REACHED` (10 entregas); `422 DELIVERY_RECIPIENT_MISSING` (ni cuerpo ni correo en el comprobante); `400` si `email` no es una dirección válida de hasta 254 caracteres. En una transacción: bloquear el documento, crear la entrega, evento `DELIVERY_REQUESTED`, auditoría `DELIVERY_REQUESTED` con el id de la entrega (sin la dirección).

- [ ] **Step 1: Escribir las pruebas** (`DeliveryApiIT`): `deliveriesAreListedNewestFirst`; `resendToTheCustomerAddress` (202; nueva entrega `PENDING`, no automática; evento y auditoría; tras `process`, segundo mensaje en el SMTP simulado); `resendToAnotherAddress`; `invalidAddressIsRejected` (400, incluida una con salto de línea); `documentWithoutAnyAddressNeedsOne` (422); `notAuthorizedDocumentCannotBeDelivered` (409); `limitPerDocument` (409 a la undécima); `auditorCannotResend` (403) `butCanList`; `foreignDocumentIsNotFound` (GET y POST 404); `contractListsTheNewRoutes` (lo cubre `OpenApiContractIT`).
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='DeliveryApiIT,OpenApiContractIT'` — Expected: 404 en las rutas nuevas.
- [ ] **Step 3: Implementar** controlador y contrato (esquemas `Delivery`, `DeliveryRequest`; errores nuevos; artefactos `ride` y `xml-authorized` con su tipo de contenido).
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`.
- [ ] **Step 5: Commit** `feat(api): entregas del comprobante`

### Task 7: Documentación y configuración

**Files:**
- Modify: `docs/05-electronic-document-flow.md`, `docs/09-error-handling.md`, `docs/04-database-design.md`, `docs/10-security.md`, `docs/11-api-specification.md`, `docs/13-deployment.md`, `docs/DEVELOPMENT.md`, `README.md`, `.env.example`, `docs/superpowers/plans/2026-10-10-plan-maestro.md`

- [ ] **Step 1:** Documentar lo implementado y sus límites: correo desactivado por defecto y nunca enviado a un servidor real; entrega al menos una vez; sin detección de rebotes; RIDE solo de factura, con Helvetica (caracteres fuera de CP1252 se omiten) y sin logotipo; webhooks en plan aparte; la licencia de OpenPDF (LGPL 2.1 / MPL 2.0).
- [ ] **Step 2: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`.
- [ ] **Step 3: Commit** `docs: RIDE, archivo y entrega`
