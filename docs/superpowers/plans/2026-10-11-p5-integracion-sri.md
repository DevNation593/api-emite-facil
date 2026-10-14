# P5 — Integración con el SRI: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que una factura emitida avance sola de `RESERVED` hasta `AUTHORIZED` (o a su estado de rechazo): el `outbox` llega a RabbitMQ, un worker genera, firma, envía a la recepción del SRI y consulta la autorización, con reintentos, reconciliación de envíos inciertos y DLQ.

**Architecture:** Un cliente SOAP 1.1 escrito sobre `java.net.http.HttpClient` detrás de la interfaz `SriGateway`, con el contrato cotejado contra los WSDL oficiales. `DocumentPipeline.advance(tenantId, documentId)` es el único punto de avance: cada paso es una transacción corta y **la llamada HTTP queda fuera de toda transacción**; el paso a `SENDING` se confirma antes de transmitir. Los reintentos se programan en la base (`next_attempt_at`) y un planificador los convierte en eventos del `outbox`; un único relevo publica el `outbox` en RabbitMQ con confirmación y un único consumidor llama al pipeline.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AMQP (RabbitMQ 4), `JdbcClient`, Flyway, `java.net.http`, Testcontainers, `com.sun.net.httpserver` (solo en pruebas, como SRI simulado), Awaitility.

**Spec:** [docs/08-sri-webservices.md](../../08-sri-webservices.md), [docs/05-electronic-document-flow.md](../../05-electronic-document-flow.md), [docs/09-error-handling.md](../../09-error-handling.md); ficha técnica off-line v2.34 (numerales 5.10–5.12, 7.2–7.5 y sección 11) y los WSDL `RecepcionComprobantesOffline` y `AutorizacionComprobantesOffline` de `celcer.sri.gob.ec`, leídos el 2026-10-11.

## Datos normativos (leídos de la fuente, no de memoria)

- **Recepción.** SOAP 1.1 document/literal, `soapAction=""`, `elementFormDefault="unqualified"`. Petición: `{http://ec.gob.sri.ws.recepcion}validarComprobante` con hijo sin espacio de nombres `xml` (`xs:base64Binary`). Respuesta: `validarComprobanteResponse/RespuestaRecepcionComprobante/{estado, comprobantes/comprobante/{claveAcceso, mensajes/mensaje/{identificador, mensaje, informacionAdicional, tipo}}}`. `estado` es `RECIBIDA` o `DEVUELTA`.
- **Autorización.** Petición: `{http://ec.gob.sri.ws.autorizacion}autorizacionComprobante` con hijo `claveAccesoComprobante`. Respuesta: `autorizacionComprobanteResponse/RespuestaAutorizacionComprobante/{claveAccesoConsultada, numeroComprobantes, autorizaciones/autorizacion/{estado, numeroAutorizacion, fechaAutorizacion (xs:dateTime), ambiente, comprobante, mensajes}}`. Estados que aparecen en la ficha: `AUTORIZADO`, `NO AUTORIZADO`, `RECHAZADO` (ejemplo del 7.2.3) y en procesamiento (PPR). Puede haber varias respuestas no autorizadas y una sola autorizada.
- **Direcciones.** Pruebas: `https://celcer.sri.gob.ec/comprobantes-electronicos-ws/{RecepcionComprobantesOffline,AutorizacionComprobantesOffline}`. Producción: las mismas rutas en `https://cel.sri.gob.ec`.
- **Consumo asíncrono (7.4).** Tras `RECIBIDA` se espera un tiempo parametrizable antes de consultar la autorización. El SRI puede tardar hasta 24 horas (5.12, 7.5).
- **Código 70** (clave de acceso en procesamiento): no reenviar ni generar otra clave; esperar la autorización. **Código 43** (clave de acceso registrada): el SRI ya tiene el comprobante. **Código 45** (secuencial registrado) es un rechazo real.
- **Reenvío (5.10, nota 1 de la sección 11).** Un comprobante rechazado se corrige y reenvía con la misma clave de acceso y secuencial. Los devueltos no se guardan en el SRI.

## Global Constraints

- Ninguna llamada HTTP al SRI dentro de una transacción de base de datos ni con una fila bloqueada.
- Un envío incierto (timeout, corte, HTTP 5xx, respuesta ilegible) **nunca** se reenvía sin consultar antes la autorización por clave de acceso. Nunca se genera otra clave ni otro secuencial.
- El estado del SRI se guarda literal (`sri_status`) además del estado interno. Un resultado que no se entiende no se inventa: queda como intento no concluyente.
- Las direcciones del SRI salen solo de la configuración y se validan al arrancar: `https` y host `celcer.sri.gob.ec` o `cel.sri.gob.ec`, salvo `einvoice.sri.allow-custom-endpoints=true`. Ningún cliente de la API envía URL.
- No se transmite nada por defecto: `einvoice.sri.enabled-environments` vacío deja los documentos en `SIGNED`.
- Las respuestas del SRI se leen con el parser seguro (sin DOCTYPE ni acceso externo) y con tope de tamaño de 2 MB.
- Los mensajes de RabbitMQ y el `outbox` llevan solo identificadores. Ni XML, ni datos del cliente, ni certificados en mensajes o logs.
- Documento ajeno = 404. Mensajes y comentarios en español; nombres de código en inglés; tabulación como el resto del código.
- Toda ejecución de Maven pasa por `.superpowers/sdd/2026-10-11-p5-integracion-sri/verify.sh <args>` (copia del de P4).
- Sin dependencias nuevas de producción.

## Decisiones de diseño

- **Estados nuevos:** `SENDING`, `RECEIVED`, `AUTHORIZED`, `RETURNED`, `NOT_AUTHORIZED`. `AUTHORIZING`, `SRI_PROCESSING` y `SRI_UNAVAILABLE` del doc 05 **no** son estados: la espera se expresa con `next_attempt_at`, `attempt_count` y la tabla `sri_attempts`.
- **Reintentos:** espera `min(base × 2^(n−1), máximo)` con ±20 % de variación. Agotado `max-attempts`, el documento queda sin programar (`next_attempt_at` nulo) y con un evento de estado `SRI_RETRIES_EXHAUSTED`; se reactiva con `POST /documents/{id}/retry`.
- **Envío en curso:** al empezar un envío se inserta el intento (`outcome` nulo) y se pasa a `SENDING`. Otro proceso que encuentre `SENDING` con un intento en curso más reciente que `send-lease` no hace nada.
- **Cola:** exchange `fiscal.events` (topic), cola `document.issue`, DLQ `document.issue.dlq` por el exchange `fiscal.dlx`. No hay cola `sri.reconcile`: reconciliar es volver a llamar a `advance`.
- **Evidencia:** la respuesta de autorización que deja el documento en `AUTHORIZED` se guarda íntegra como artefacto `SRI_AUTHORIZATION`. El resto de respuestas van a `sri_attempts.response`.

## Review Focus

1. **Dos workers toman el mismo documento `SIGNED` a la vez** → un solo `validarComprobante` (Task 3, `concurrentAdvanceSendsOnce`).
2. **El proceso muere entre confirmar `SENDING` y recibir la respuesta** → al vencer `send-lease` se consulta la autorización; solo se reenvía si el SRI no conoce la clave (Task 3, `abandonedSendIsResolvedByQueryingFirst`).
3. **El SRI responde algo que no es su contrato** (página HTML de error con 200, cuerpo vacío, XML con DOCTYPE, 5 MB) → intento no concluyente, sin excepción ni cambio de estado (Task 1 y Task 2).
4. **La autorización habla de otra clave de acceso o de otro ambiente** → no se acepta (Task 4, `authorizationForAnotherKeyOrEnvironmentIsNotAccepted`).
5. **Ambiente no habilitado** → ninguna petición HTTP (Task 3, `disabledEnvironmentStaysSignedWithoutCallingTheSri`).

---

### Task 1: Esquema V5 y lectura de respuestas del SRI

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__sri_transmission.sql`
- Create: `backend/src/main/java/com/example/einvoice/xml/SecureXml.java`
- Modify: `backend/src/main/java/com/example/einvoice/signature/XadesProfile.java` (`parse` delega en `SecureXml.parse`)
- Create: `backend/src/main/java/com/example/einvoice/sri/{SriEnvironment,SriMessage,ReceptionResult,AuthorizationResult,SriResponseParser}.java`
- Test: `backend/src/test/java/com/example/einvoice/sri/SriResponseParserTest.java`, `backend/src/test/java/com/example/einvoice/sri/SriSoapSamples.java`
- Test: `backend/src/test/java/com/example/einvoice/SchemaIT.java` o la prueba de esquema existente, si la hay (añadir las tablas nuevas)

**Interfaces:**
- Produces:
  - `SecureXml.parse(byte[] xml) : Document throws SAXException, IOException`
  - `enum SriEnvironment { TEST("1"), PRODUCTION("2") }` con `code()`, `static SriEnvironment fromCode(String)`, y `matchesLabel(String sriLabel)` (`PRUEBAS…` ↔ TEST, `PRODUC…` ↔ PRODUCTION, sin distinguir mayúsculas ni tildes)
  - `record SriMessage(String identifier, String message, String additionalInfo, String type)`
  - `record ReceptionResult(Outcome outcome, String sriStatus, List<SriMessage> messages, Integer httpStatus, String errorDetail, byte[] rawResponse)` con `enum Outcome { RECEIVED, RETURNED, FAILED }`, `static ReceptionResult failed(Integer httpStatus, String errorDetail, byte[] raw)` y `boolean hasMessage(String identifier)`
  - `record AuthorizationResult(Outcome outcome, String sriStatus, String consultedAccessKey, String authorizationNumber, OffsetDateTime authorizedAt, String environmentLabel, List<SriMessage> messages, Integer httpStatus, String errorDetail, byte[] rawResponse)` con `enum Outcome { AUTHORIZED, NOT_AUTHORIZED, PROCESSING, NOT_FOUND, FAILED }` y `static AuthorizationResult failed(...)`
  - `SriResponseParser.reception(byte[] body) : ReceptionResult` y `SriResponseParser.authorization(byte[] body) : AuthorizationResult` — nunca lanzan; devuelven `FAILED` con `errorDetail`
  - Tablas y columnas de V5 (abajo)

V5:

```sql
ALTER TABLE fiscal_documents
 ADD COLUMN next_attempt_at timestamptz,
 ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
 ADD COLUMN authorization_number varchar(49),
 ADD COLUMN authorized_at timestamptz;
CREATE INDEX idx_fiscal_documents_due ON fiscal_documents (next_attempt_at) WHERE next_attempt_at IS NOT NULL;

CREATE TABLE sri_attempts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 operation varchar(20) NOT NULL CHECK (operation IN ('RECEPTION','AUTHORIZATION')),
 environment varchar(1) NOT NULL CHECK (environment IN ('1','2')),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 completed_at timestamptz,
 outcome varchar(30),          -- nulo mientras el intento está en curso
 sri_status varchar(60),       -- estado literal devuelto por el SRI
 http_status integer,
 error_detail text,
 response bytea,
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id)
);
CREATE INDEX idx_sri_attempts_document ON sri_attempts (document_id, started_at);

CREATE TABLE sri_messages (
 attempt_id uuid NOT NULL REFERENCES sri_attempts(id),
 position integer NOT NULL,
 identifier varchar(20),
 message text,
 additional_info text,
 type varchar(30),
 PRIMARY KEY (attempt_id, position)
);

ALTER TABLE document_artifacts DROP CONSTRAINT document_artifacts_kind_check;
ALTER TABLE document_artifacts ADD CONSTRAINT document_artifacts_kind_check
 CHECK (kind IN ('XML_UNSIGNED','XML_SIGNED','SRI_AUTHORIZATION'));
```

Reglas del parser: busca los elementos por nombre local, sin exigir espacio de nombres (el WSDL los declara como `ref` pero la ficha los muestra sin prefijo). Recepción: `RECIBIDA` → `RECEIVED`; `DEVUELTA` → `RETURNED`; otro valor o sin `estado` → `FAILED`. Autorización: sin ninguna `autorizacion` → `NOT_FOUND`; si alguna es `AUTORIZADO` → `AUTHORIZED` con sus datos; si no, la de `fechaAutorizacion` más reciente: `NO AUTORIZADO` o `RECHAZADO` → `NOT_AUTHORIZED`, cualquier otro estado → `PROCESSING`. Un `AUTORIZADO` sin `numeroAutorizacion` (dígitos, 10 a 49) o sin `fechaAutorizacion` legible → `FAILED`. Un `soap:Fault` → `FAILED`. Cada texto de mensaje se recorta a 2000 caracteres; como mucho 100 mensajes.

- [ ] **Step 1: Escribir las pruebas** en `SriResponseParserTest` con las muestras de `SriSoapSamples` (los cuatro ejemplos del numeral 7.2.3 de la ficha, parametrizados por clave de acceso):
  - `receivedHasNoMessages`: muestra "Recepción exitosa" → `RECEIVED`, `sriStatus` `"RECIBIDA"`, mensajes vacíos.
  - `returnedCarriesEveryMessage`: muestra "Recepción fallida" → `RETURNED`, un mensaje con `identifier` `"35"`, `type` `"ERROR"`, y `hasMessage("35")`.
  - `authorizedCarriesNumberDateAndEnvironment`: → `AUTHORIZED`, `authorizationNumber` igual a la clave, `authorizedAt` `2012-03-05T16:57:34.997-05:00`, `environmentLabel` `"PRUEBAS"`, mensaje `60` `ADVERTENCIA`.
  - `rejectedIsNotAuthorized` (`RECHAZADO`) y `noAutorizadoIsNotAuthorized` (`NO AUTORIZADO`) → `NOT_AUTHORIZED` con el mensaje `46`.
  - `oneAuthorizedWinsOverEarlierRejections`: dos `NO AUTORIZADO` y un `AUTORIZADO` → `AUTHORIZED`.
  - `latestRejectionIsReported`: dos `NO AUTORIZADO` con fechas distintas → mensajes del más reciente.
  - `noAuthorizationsMeansNotFound`: `numeroComprobantes` 0 y `<autorizaciones/>` → `NOT_FOUND`.
  - `unknownStateIsProcessing`: `EN PROCESO` → `PROCESSING` con `sriStatus` literal.
  - `garbageNeverThrows`: parametrizada con cuerpo vacío, HTML, XML con `<!DOCTYPE`, XML bien formado sin el elemento de respuesta, `soap:Fault`, `AUTORIZADO` sin número, `AUTORIZADO` con fecha ilegible → `FAILED` con `errorDetail` no vacío.
  - `environmentLabels`: `TEST.matchesLabel("PRUEBAS")`, `PRODUCTION.matchesLabel("PRODUCCIÓN")` y `("PRODUCCION")` verdaderos; cruzados falsos.
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh test -Dtest=SriResponseParserTest` — Expected: error de compilación (clases inexistentes).
- [ ] **Step 3: Implementar** V5, `SecureXml`, los tipos y el parser.
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`, incluidas las pruebas de firma (usan `SecureXml` vía `XadesProfile`) y la validación de esquema de Hibernate.
- [ ] **Step 5: Commit** `feat(sri): esquema de transmisión y lectura de respuestas del SRI`

### Task 2: Cliente SOAP y SRI simulado

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/sri/{SriGateway,HttpSriGateway,SriProperties,SriSoapRequests}.java`
- Modify: `backend/src/main/resources/application.yml` (bloque `einvoice.sri`)
- Test: `backend/src/test/java/com/example/einvoice/support/SriStub.java`
- Test: `backend/src/test/java/com/example/einvoice/sri/{HttpSriGatewayTest,SriPropertiesTest}.java`

**Interfaces:**
- Consumes: Task 1 (`SriResponseParser`, resultados, `SriEnvironment`).
- Produces:
  - `interface SriGateway { ReceptionResult receive(SriEnvironment env, byte[] signedXml); AuthorizationResult authorize(SriEnvironment env, String accessKey); }` — nunca lanza por fallos de red; devuelve `FAILED`.
  - `@ConfigurationProperties("einvoice.sri") record SriProperties(Set<String> enabledEnvironments, boolean allowCustomEndpoints, Endpoints test, Endpoints production, Duration connectTimeout, Duration readTimeout, Duration authorizationDelay, Duration retryBackoff, Duration retryMaxBackoff, int retryMaxAttempts)` con `record Endpoints(URI reception, URI authorization)`, `boolean enabled(SriEnvironment)`, `Endpoints endpoints(SriEnvironment)`, `Duration sendLease()` = `connectTimeout + readTimeout + 30 s`. El constructor valida la lista blanca y lanza `IllegalStateException` con un mensaje que nombra la propiedad.
  - `SriSoapRequests.reception(byte[] signedXml) : byte[]` y `.authorization(String accessKey) : byte[]`
  - Valores por defecto en `application.yml`: `enabled-environments: ${SRI_ENABLED_ENVIRONMENTS:}`, `allow-custom-endpoints: false`, las cuatro direcciones oficiales (sin `?wsdl`), `connect-timeout: ${SRI_CONNECT_TIMEOUT:10s}`, `read-timeout: ${SRI_READ_TIMEOUT:60s}`, `authorization-delay: ${SRI_AUTHORIZATION_DELAY:3s}`, `retry-backoff: ${SRI_RETRY_BACKOFF:15s}`, `retry-max-backoff: 1h`, `retry-max-attempts: ${SRI_RETRY_MAX:30}`.
  - Pruebas: `SriStub` — un servidor HTTP único por JVM (`SriStub.instance()`), con `receptionUrl()`, `authorizationUrl()`, y guiones por clave de acceso: `onReception(accessKey, Reply...)`, `onAuthorization(accessKey, Reply...)` (cada petición consume la siguiente respuesta; la última se repite), `Reply.xml(String body)`, `Reply.status(int, String body)`, `Reply.delay(Duration, Reply)`, `Reply.dropConnection()`; contadores `receptionCalls(accessKey)`, `authorizationCalls(accessKey)`, `lastReceptionXml(accessKey)`. Sin guion: recepción `RECIBIDA` y autorización `AUTORIZADO` (ambiente `PRUEBAS`, número = clave). Extrae la clave del `<claveAcceso>` del XML decodificado o de `claveAccesoComprobante`.

`HttpSriGateway`: `HttpClient` HTTP/1.1, sin redirecciones, `connectTimeout`; cada petición con `timeout(readTimeout)`, `Content-Type: text/xml; charset=UTF-8`, `SOAPAction: ""`. Lee como mucho 2 MB del cuerpo (más → `FAILED` "respuesta demasiado grande"). HTTP distinto de 200 → `FAILED` con el código (el cuerpo se conserva en `rawResponse`, recortado). `IOException`/timeout → `FAILED` con el nombre de la clase de la excepción, sin el mensaje si contiene la URL. `InterruptedException` → restaura la interrupción y `FAILED`.

- [ ] **Step 1: Escribir las pruebas.**
  - `SriPropertiesTest`: `officialHostsAreAccepted`; `otherHostIsRejectedUnlessCustomEndpointsAreAllowed` (`https://ejemplo.test/x` → `IllegalStateException` cuyo mensaje contiene `einvoice.sri`); `plainHttpIsRejected`; `environmentIsEnabledOnlyWhenListed`; `sendLeaseCoversBothTimeouts`.
  - `HttpSriGatewayTest` (usa `SriStub`, sin Spring): `receptionSendsTheSignedXmlAsBase64InTheOfficialEnvelope` (el stub recibió `Content-Type` `text/xml`, cabecera `SOAPAction`, raíz `Envelope` de SOAP 1.1, elemento `{http://ec.gob.sri.ws.recepcion}validarComprobante` con hijo `xml` sin espacio de nombres cuyo Base64 decodifica a los bytes enviados); `authorizationSendsTheAccessKey` (elemento `{http://ec.gob.sri.ws.autorizacion}autorizacionComprobante/claveAccesoComprobante`); `receivedAndReturnedAreParsed`; `http500IsAFailureThatKeepsTheStatus`; `htmlWith200IsAFailure`; `slowAnswerTimesOut` (`readTimeout` 300 ms, respuesta a 2 s → `FAILED` en menos de 1,5 s); `droppedConnectionIsAFailure`; `nothingListeningIsAFailure`; `oversizedAnswerIsAFailure` (3 MB).
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh test -Dtest='HttpSriGatewayTest,SriPropertiesTest'` — Expected: error de compilación.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`. Las pruebas de integración existentes arrancan con los valores por defecto (direcciones oficiales, nada habilitado).
- [ ] **Step 5: Commit** `feat(sri): cliente SOAP de recepción y autorización`

### Task 3: Pipeline hasta la recepción

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/sri/SriAttemptLog.java`
- Create: `backend/src/main/java/com/example/einvoice/orchestration/{DocumentPipeline,RetryPolicy}.java`
- Modify: `backend/src/main/java/com/example/einvoice/billing/FiscalDocumentRepository.java` (lectura y escritura de `next_attempt_at`, `attempt_count`; `ProcessingState` gana `environment` y `accessKey`)
- Modify: `backend/src/test/java/com/example/einvoice/support/AbstractIntegrationTest.java` (propiedades del SRI simulado: `einvoice.sri.allow-custom-endpoints=true`, direcciones del stub, `enabled-environments=1`, `authorization-delay=0s`, `retry-backoff=1s`, `read-timeout=2s`, `connect-timeout=2s`)
- Test: `backend/src/test/java/com/example/einvoice/orchestration/{RetryPolicyTest,ReceptionPipelineIT}.java`

**Interfaces:**
- Consumes: `DocumentProcessor.generateAndSign`, `SriGateway`, `SriProperties`, `ArtifactStore.find`, `FiscalDocumentRepository.lockForProcessing/transition/recordStateChange`.
- Produces:
  - `RetryPolicy.delay(int attempt, Duration base, Duration max, double jitter01) : Duration` — `attempt` ≥ 1; `jitter01` en [0,1) se traduce a un factor entre 0,8 y 1,2.
  - `SriAttemptLog.start(tenantId, documentId, operation, environment) : UUID`; `.complete(attemptId, outcome, ReceptionResult|AuthorizationResult, boolean keepResponse)`; `.abandonInFlight(documentId)`; `.inFlightSince(documentId, operation) : Optional<Instant>`; `.list(tenantId, documentId) : List<AttemptView>` (con sus mensajes).
  - `DocumentPipeline.advance(UUID tenantId, UUID documentId) : String` — estado en que queda. No es `@Transactional`; usa `TransactionTemplate` por paso. Lanza `ApiException.notFound()` si el documento no es de esa empresa.
  - Resultados de recepción guardados en `sri_attempts.outcome`: `RECEIVED`, `ALREADY_REGISTERED`, `RETURNED`, `UNCERTAIN`, `ABANDONED`.

Comportamiento de `advance` (en este orden; se repite mientras el estado cambie y haya paso inmediato):

| Estado al entrar | Acción |
|---|---|
| `RESERVED`, `GENERATED`, `SIGNATURE_FAILED` | `generateAndSign`. Si no queda en `SIGNED`, termina (nada programado). |
| `SIGNED`, ambiente no habilitado | Termina sin llamar al SRI; `next_attempt_at` nulo. |
| `SIGNED`, habilitado | Tx: bloquear, comprobar `SIGNED`, `start(RECEPTION)`, `SIGNED→SENDING`, `next_attempt_at = ahora + sendLease`. Fuera de tx: `receive`. Tx: bloquear, si sigue `SENDING` aplicar el resultado. |
| `SENDING` con intento en curso más reciente que `sendLease` | Nada. |
| `SENDING` sin intento vivo | `abandonInFlight`; fuera de tx `authorize`; después: `AUTHORIZED`/`NOT_AUTHORIZED`/`PROCESSING` → `SENDING→RECEIVED` y sigue en Task 4; `NOT_FOUND` → `SENDING→SIGNED` con motivo `RESEND_AFTER_NOT_FOUND` y se envía de nuevo en la misma llamada (una sola vez por llamada); `FAILED` → reintento programado. |

Aplicación del resultado de `receive`: `RECEIVED` → `SENDING→RECEIVED`, `attempt_count = 0`, `next_attempt_at = ahora + authorizationDelay`. `RETURNED` con algún mensaje `43` o `70` → igual, con resultado `ALREADY_REGISTERED`. Otro `RETURNED` → `SENDING→RETURNED`, motivo = identificadores de los mensajes separados por coma, nada programado. `FAILED` → sigue `SENDING`, resultado `UNCERTAIN`, `attempt_count + 1`, `next_attempt_at = ahora + RetryPolicy.delay(...)`; si `attempt_count` alcanza `retryMaxAttempts`: `next_attempt_at` nulo y evento `SENDING→SENDING` con motivo `SRI_RETRIES_EXHAUSTED`.

- [ ] **Step 1: Escribir las pruebas.**
  - `RetryPolicyTest`: `growsExponentiallyUpToTheMaximum` (base 15 s, máx 1 h, jitter 0,5 → 15 s, 30 s, 60 s, …, 1 h a partir del intento 9, y nunca desborda con intento 1000); `jitterStaysWithinTwentyPercent` (jitter 0 → ×0,8; 0,999 → < ×1,2).
  - `ReceptionPipelineIT` (cada prueba: emisor con certificado y factura nueva; la clave de acceso guía al stub):
    - `signedInvoiceIsSentAndReceived`: `advance` desde `RESERVED` → `RECEIVED`; el stub recibió una vez exactamente los bytes de `XML_SIGNED`; transiciones `…>SIGNED`, `SIGNED>SENDING`, `SENDING>RECEIVED`; un intento `RECEPTION` `RECEIVED` con `sri_status` `RECIBIDA`; `next_attempt_at` no nulo.
    - `returnedInvoiceKeepsEveryMessage`: `DEVUELTA` con mensajes 35 y 45 → `RETURNED`; dos filas en `sri_messages`; motivo `35, 45`; `next_attempt_at` nulo; la respuesta cruda guardada.
    - `alreadyRegisteredCountsAsReceived`: `DEVUELTA` con 70 → `RECEIVED`, resultado `ALREADY_REGISTERED`; ídem con 43.
    - `uncertainSendIsNeverResentBlindly`: recepción con HTTP 500; `advance` → `SENDING`, intento `UNCERTAIN`, `attempt_count` 1, `next_attempt_at` futuro. Segundo `advance` con autorización `AUTORIZADO` en el stub → **cero** recepciones nuevas (`receptionCalls` sigue en 1) y el documento sale de `SENDING`.
    - `uncertainSendIsResentOnlyWhenTheSriDoesNotKnowTheKey`: recepción 500 y luego `RECIBIDA`; autorización `NOT_FOUND` → segundo `advance` termina en `RECEIVED` con `receptionCalls` 2 y transición `SENDING>SIGNED` con motivo `RESEND_AFTER_NOT_FOUND`.
    - `abandonedSendIsResolvedByQueryingFirst`: se simula la muerte del proceso dejando el documento en `SENDING` con un intento en curso de hace 10 minutos (SQL) → `advance` marca el intento `ABANDONED`, consulta la autorización antes de cualquier recepción.
    - `sendInFlightIsLeftAlone`: `SENDING` con intento en curso recién iniciado → `advance` devuelve `SENDING` sin ninguna llamada al stub.
    - `concurrentAdvanceSendsOnce`: 4 hilos sobre un documento `SIGNED` con recepción retrasada 500 ms → `receptionCalls` 1.
    - `disabledEnvironmentStaysSignedWithoutCallingTheSri`: emisor con `defaultEnvironment` `"2"` → `SIGNED`, cero llamadas, `next_attempt_at` nulo.
    - `retriesAreExhausted`: con `attempt_count` puesto a `retryMaxAttempts − 1` (SQL) y recepción y autorización fallando → `next_attempt_at` nulo y motivo `SRI_RETRIES_EXHAUSTED` en el último evento.
    - `signatureFailureDoesNotReachTheSri`: sin certificado → `SIGNATURE_FAILED`, cero llamadas.
    - `foreignDocumentIsNotFound`.
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest=RetryPolicyTest -Dit.test=ReceptionPipelineIT` — Expected: error de compilación.
- [ ] **Step 3: Implementar** `RetryPolicy`, `SriAttemptLog`, los cambios del repositorio y `DocumentPipeline` hasta `RECEIVED` (el paso de autorización queda como "nada que hacer" hasta la Task 4).
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`.
- [ ] **Step 5: Commit** `feat(orchestration): envío a la recepción del SRI con reconciliación de envíos inciertos`

### Task 4: Autorización

**Files:**
- Modify: `backend/src/main/java/com/example/einvoice/orchestration/DocumentPipeline.java`
- Modify: `backend/src/main/java/com/example/einvoice/storage/ArtifactKind.java` (`SRI_AUTHORIZATION("sri-authorization")`)
- Modify: `backend/src/main/java/com/example/einvoice/billing/FiscalDocumentRepository.java` (`markAuthorized(tenantId, documentId, number, authorizedAt)`)
- Test: `backend/src/test/java/com/example/einvoice/orchestration/AuthorizationPipelineIT.java`

**Interfaces:**
- Consumes: Task 3 completo.
- Produces: `advance` sobre `RECEIVED`: fuera de tx `start(AUTHORIZATION)` + `authorize`; tx: bloquear y, si sigue `RECEIVED`, aplicar. Resultados en `sri_attempts.outcome`: `AUTHORIZED`, `NOT_AUTHORIZED`, `PROCESSING`, `NOT_FOUND`, `MISMATCH`, `UNCERTAIN`.

Aplicación: `AUTHORIZED` con `consultedAccessKey` igual a la clave del documento y `environmentLabel` del ambiente del documento → guardar `rawResponse` como `SRI_AUTHORIZATION`, `markAuthorized`, `RECEIVED→AUTHORIZED`, nada programado, intento sin `response` (ya está en el artefacto). `AUTHORIZED` que no cumple esa correlación → resultado `MISMATCH`, tratado como no concluyente. `NOT_AUTHORIZED` → `RECEIVED→NOT_AUTHORIZED`, motivo = identificadores, mensajes guardados, nada programado. `PROCESSING`, `NOT_FOUND`, `FAILED`, `MISMATCH` → sigue `RECEIVED`, `attempt_count + 1`, reintento con `RetryPolicy`, y agotamiento como en la Task 3 (`RECEIVED→RECEIVED`, `SRI_RETRIES_EXHAUSTED`). Un `NOT_AUTHORIZED` cuya clave consultada no coincide también es `MISMATCH`.

- [ ] **Step 1: Escribir las pruebas** (`AuthorizationPipelineIT`):
  - `receivedInvoiceBecomesAuthorized`: `advance` completo desde `RESERVED` → `AUTHORIZED`; `authorization_number` = clave, `authorized_at` = fecha del stub; artefacto `SRI_AUTHORIZATION` idéntico a la respuesta del stub; `next_attempt_at` nulo; transiciones terminan en `RECEIVED>AUTHORIZED`; mensaje `60` guardado.
  - `notAuthorizedKeepsTheReasons`: `NO AUTORIZADO` con mensajes 39 y 52 → `NOT_AUTHORIZED`, motivo `39, 52`, sin artefacto de autorización.
  - `processingIsPolledAgainLater`: `EN PROCESO` → sigue `RECEIVED`, `attempt_count` 1, `next_attempt_at` futuro; con la siguiente respuesta `AUTORIZADO`, un nuevo `advance` → `AUTHORIZED`.
  - `notFoundAfterReceptionKeepsWaiting`: `NOT_FOUND` → `RECEIVED`, **sin** reenvío (`receptionCalls` 1).
  - `authorizationForAnotherKeyOrEnvironmentIsNotAccepted`: respuesta `AUTORIZADO` con otra `claveAccesoConsultada`, y otra con ambiente `PRODUCCIÓN` → `RECEIVED`, resultado `MISMATCH`, sin artefacto.
  - `sriDownWhileAuthorizingIsRetried`: HTTP 503 → `RECEIVED`, `UNCERTAIN`.
  - `uncertainSendThatWasAuthorizedEndsAuthorized`: recepción 500, autorización `AUTORIZADO` → dos `advance` → `AUTHORIZED` con `receptionCalls` 1.
  - `authorizedDocumentIsNeverTouchedAgain`: `advance` sobre `AUTHORIZED` → cero llamadas, mismo `version`.
  - `concurrentAuthorizationStoresOneArtifact`: 4 hilos sobre `RECEIVED` → un artefacto, una transición a `AUTHORIZED`.
  - `artifactIsDownloadable`: `GET /api/v1/documents/{id}/artifacts/sri-authorization` → 200 con los bytes.
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=AuthorizationPipelineIT` — Expected: fallan por quedarse en `RECEIVED`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`.
- [ ] **Step 5: Commit** `feat(orchestration): consulta de autorización y cierre del comprobante`

### Task 5: Mensajería — relevo del outbox, worker, planificador y DLQ

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/orchestration/{MessagingConfig,OutboxRelay,DocumentWorker,DueDocumentScheduler,WorkerSchedule}.java`
- Modify: `backend/src/main/resources/application.yml` (`einvoice.worker.*`, confirmaciones y reintento del listener de RabbitMQ)
- Modify: `backend/src/test/java/com/example/einvoice/support/AbstractIntegrationTest.java` (`einvoice.worker.enabled=false`)
- Test: `backend/src/test/java/com/example/einvoice/orchestration/{OutboxRelayIT,DueDocumentSchedulerIT,PipelineEndToEndIT}.java`

**Interfaces:**
- Consumes: `DocumentPipeline.advance`, `OutboxWriter.write`.
- Produces:
  - Topología (beans siempre presentes): exchange topic `fiscal.events`; cola durable `document.issue` enlazada con la clave `document.issue` y con `x-dead-letter-exchange=fiscal.dlx`, `x-dead-letter-routing-key=document.issue.dlq`; exchange directo `fiscal.dlx`; cola `document.issue.dlq`.
  - `OutboxRelay.publishPending() : int` — una transacción: hasta 50 eventos sin despachar `FOR UPDATE SKIP LOCKED`, publicados como mensajes persistentes JSON `{"eventId","tenantId","documentId","eventType"}` con `messageId = eventId`, espera la confirmación del broker y marca `dispatched_at`. Si la confirmación falla, la transacción se deshace.
  - `DocumentWorker.handle(Message)` — `@RabbitListener(queues = "document.issue")`, activo solo con `einvoice.worker.enabled=true`; pone `correlationId = eventId` en el MDC y llama a `advance`. Un documento inexistente se descarta con un aviso en el log (no va a la DLQ). Cualquier otra excepción se reintenta 3 veces y el mensaje acaba en la DLQ.
  - `DueDocumentScheduler.enqueueDue() : int` — una transacción: hasta 100 documentos con `next_attempt_at <= now()` (`FOR UPDATE SKIP LOCKED`); escribe un evento `DOCUMENT_DUE` por cada uno y pone `next_attempt_at = now() + einvoice.worker.redelivery-lease` (5 min por defecto).
  - `WorkerSchedule` — `@Scheduled(fixedDelayString = "${einvoice.worker.poll-interval:1s}")` que llama a los dos anteriores; existe solo con `einvoice.worker.enabled=true` (por defecto `true`).

- [ ] **Step 1: Escribir las pruebas.**
  - `OutboxRelayIT` (worker apagado; lee la cola con `RabbitTemplate.receive`): `pendingEventsArePublishedOnceAndMarked` (emitir una factura → `publishPending()` ≥ 1; el mensaje de esa factura está en `document.issue` con su `tenantId`/`documentId`/`eventType` `INVOICE_RESERVED`; `dispatched_at` no nulo; una segunda llamada no la vuelve a publicar); `messageCarriesOnlyIdentifiers` (el cuerpo tiene exactamente esas cuatro claves); `rejectedMessageGoesToTheDeadLetterQueue` (se recibe con `basicReject(requeue=false)` vía `RabbitTemplate.execute` y aparece en `document.issue.dlq`).
  - `DueDocumentSchedulerIT`: `dueDocumentGetsAnEventAndALease` (documento con `next_attempt_at` pasado → un `DOCUMENT_DUE` en el outbox y `next_attempt_at` futuro; segunda llamada no lo repite); `futureAndUnscheduledDocumentsAreIgnored`.
  - `PipelineEndToEndIT` (`@TestPropertySource(properties = "einvoice.worker.enabled=true")`, contexto propio): `issuedInvoiceEndsAuthorizedWithoutAnyManualStep` (certificado cargado, `POST /invoices` → con Awaitility, hasta 30 s, `GET /documents/{id}` muestra `AUTHORIZED`); `processingInvoiceIsAuthorizedOnALaterPoll` (autorización `EN PROCESO` y luego `AUTORIZADO` → `AUTHORIZED` gracias al planificador); `poisonMessageEndsInTheDeadLetterQueue` (mensaje con cuerpo ilegible publicado en `document.issue` → aparece en la DLQ y el worker sigue procesando la siguiente factura).
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='OutboxRelayIT,DueDocumentSchedulerIT,PipelineEndToEndIT'` — Expected: error de compilación.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`; las pruebas anteriores que esperan `RESERVED` tras emitir siguen pasando (worker apagado en su contexto).
- [ ] **Step 5: Commit** `feat(orchestration): relevo del outbox, worker y reintentos programados`

### Task 6: API — reintento manual, intentos del SRI y datos de autorización

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/orchestration/DocumentRetryController.java`
- Create: `backend/src/main/java/com/example/einvoice/sri/SriAttemptController.java`
- Modify: `backend/src/main/java/com/example/einvoice/billing/{DocumentController,FiscalDocumentRepository}.java` (`DocumentView` gana `authorizationNumber`, `authorizedAt`)
- Modify: `contracts/openapi.yaml` (versión 0.5.0)
- Test: `backend/src/test/java/com/example/einvoice/orchestration/DocumentRetryApiIT.java`, `backend/src/test/java/com/example/einvoice/sri/SriAttemptApiIT.java`

**Interfaces:**
- Consumes: `SriAttemptLog.list`, `OutboxWriter.write`, `AuditLog.record`, `TenantAccess.requireBilling()`.
- Produces:
  - `POST /api/v1/documents/{id}/retry` → `202` `{ "id", "state" }`. Roles `OWNER`, `ADMIN`, `BILLER` (`AUDITOR` → 403). Solo en estados `RESERVED`, `GENERATED`, `SIGNATURE_FAILED`, `SIGNED`, `SENDING`, `RECEIVED`; en otro → `409 DOCUMENT_NOT_RETRYABLE`. En una transacción: bloquear, `attempt_count = 0`, `next_attempt_at = now()`, evento `DOCUMENT_RETRY_REQUESTED` en el outbox, auditoría `DOCUMENT_RETRY_REQUESTED`.
  - `GET /api/v1/documents/{id}/sri-attempts` → `200` lista, el más reciente primero: `{ id, operation, outcome, sriStatus, httpStatus, startedAt, completedAt, messages: [{identifier, message, additionalInfo, type}] }`. Nunca la respuesta cruda. Documento ajeno → 404.
  - `GET /api/v1/documents/{id}`: `authorizationNumber` y `authorizedAt` (nulos hasta `AUTHORIZED`).

- [ ] **Step 1: Escribir las pruebas.**
  - `DocumentRetryApiIT`: `retrySchedulesTheDocumentAndLeavesAnEvent` (factura sin certificado llevada a `SIGNATURE_FAILED`; `POST …/retry` → 202; `next_attempt_at` ≤ ahora, `attempt_count` 0, un evento `DOCUMENT_RETRY_REQUESTED` sin despachar, una fila de auditoría); `retryAfterUploadingTheCertificateGetsItAuthorized` (tras el reintento, `advance` → `AUTHORIZED`); `terminalDocumentIsNotRetryable` (`AUTHORIZED`, `RETURNED`, `NOT_AUTHORIZED`, `XSD_INVALID` → 409 `DOCUMENT_NOT_RETRYABLE`); `auditorCannotRetry` (403); `foreignDocumentIsNotFound` (404); `exhaustedDocumentIsRevived` (`next_attempt_at` nulo y `attempt_count` al máximo → tras el reintento vuelve a intentarse).
  - `SriAttemptApiIT`: `attemptsAreListedNewestFirstWithTheirMessages` (factura devuelta con 35 → un intento `RECEPTION` `RETURNED` con su mensaje); `rawResponseIsNeverExposed` (el JSON no contiene `response` ni `Envelope`); `foreignDocumentIsNotFound`; `documentShowsItsAuthorization` (`authorizationNumber` y `authorizedAt` en `GET /documents/{id}`).
  - `OpenApiContractIT` existente debe fallar hasta que el contrato tenga las dos rutas nuevas.
- [ ] **Step 2: Verificar que fallan.** Run: `verify.sh verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='DocumentRetryApiIT,SriAttemptApiIT'` — Expected: 404 en las rutas nuevas.
- [ ] **Step 3: Implementar** controladores y contrato (esquemas `SriAttempt`, `SriMessage`, `DocumentRetryAccepted`; error `DOCUMENT_NOT_RETRYABLE`; estados nuevos en la descripción de `state`; artefacto `sri-authorization`).
- [ ] **Step 4: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`, incluido `OpenApiContractIT`.
- [ ] **Step 5: Commit** `feat(api): reintento de comprobantes e intentos ante el SRI`

### Task 7: Documentación y configuración

**Files:**
- Modify: `docs/05-electronic-document-flow.md` (estados reales, estado de implementación, qué pasa en cada fallo)
- Modify: `docs/08-sri-webservices.md` (contrato cotejado, cliente propio en lugar de clases generadas, variables reales, política de reintentos, códigos 43/45/70)
- Modify: `docs/09-error-handling.md` (topología real de RabbitMQ, DLQ, runbook con las rutas nuevas)
- Modify: `docs/04-database-design.md` (`sri_attempts`, `sri_messages`, columnas nuevas)
- Modify: `docs/11-api-specification.md`, `docs/12-testing-certification.md` (gate de aceptación real del SRI sigue abierto), `docs/02-sri-compliance.md` (mensajes de error ya leídos)
- Modify: `docs/DEVELOPMENT.md`, `README.md` (cómo habilitar la transmisión), `.env.example` (`SRI_ENABLED_ENVIRONMENTS` y tiempos), `docs/superpowers/plans/2026-10-10-plan-maestro.md` (P5 hecho, decisiones)

- [ ] **Step 1:** Actualizar cada documento con lo implementado y **sus límites**: no se ha transmitido nada al SRI real; reenvío corregido de `RETURNED`/`NOT_AUTHORIZED` pendiente; anulación fuera de alcance; lotes no implementados.
- [ ] **Step 2: Verificar.** Run: `verify.sh verify` — Expected: `BUILD SUCCESS`. Run: `grep -rn "Plan 5" docs/*.md` — Expected: ninguna frase que siga diciendo que el envío al SRI está pendiente.
- [ ] **Step 3: Commit** `docs: integración con el SRI`
