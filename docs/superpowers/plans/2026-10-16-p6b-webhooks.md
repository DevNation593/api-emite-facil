# Plan 6b — Webhooks salientes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que el sistema de una empresa (su ERP, su tienda) se entere por sí solo de lo que pasa con cada comprobante: recibido, autorizado, rechazado, entregado. Hoy solo puede saberlo mirando la pantalla.

**Architecture:** una empresa registra hasta diez direcciones (suscripciones) y elige qué eventos quiere en cada una. Cuando un comprobante cambia de estado, en la misma transacción se anota una notificación pendiente por cada suscripción interesada. Un planificador las devuelve a una cola propia de RabbitMQ y un worker las envía por HTTPS, firmadas, con reintentos; igual que las entregas por correo del Plan 6, pero en su propia cola para que un receptor lento no frene ni al SRI ni al correo.

**Tech Stack:** el de los planes anteriores; nada nuevo. El envío usa sockets del JDK (no el cliente HTTP) para poder fijar la dirección IP ya comprobada.

**Spec:** [doc 11](../../11-api-specification.md) ("Webhooks salientes": eventos `document.received`, `document.authorized`, `document.rejected`, `document.delivered`; `eventId` y marca de tiempo, firma HMAC del cuerpo, reintento, deduplicación y secreto rotatorio; entrega al menos una vez), [plan maestro](2026-10-10-plan-maestro.md) (P6: "los webhooks pasan a un plan propio, P6b"), [doc 10](../../10-security.md).

**Por qué no el Plan 9:** sigue bloqueado; faltan los XSD oficiales de los demás comprobantes.

## Global Constraints

- **Apagado por defecto** (`einvoice.webhooks.enabled=false`, `EINVOICE_WEBHOOKS_ENABLED`), como el correo y la transmisión al SRI: llamar a direcciones que eligen los clientes es decisión de quien opera. Apagado: crear o reactivar una suscripción responde `409 WEBHOOKS_DISABLED`, no se anotan notificaciones y no se envía nada.
- **La dirección la elige el cliente, así que no se confía en ella.** Solo `https`, sin usuario ni contraseña en la URL, sin fragmento, hasta 2048 caracteres. El nombre debe resolver solo a direcciones públicas: se rechazan loopback, redes privadas, enlace local (incluida `169.254.169.254`), CGNAT, multidifusión, sin especificar, reservadas, y sus equivalentes IPv6 (incluidas las IPv4 mapeadas). Se comprueba al registrar **y en cada envío**, y la conexión se hace a la dirección comprobada, no a lo que el nombre resuelva un instante después. No se siguen redirecciones. De la respuesta solo se lee la línea de estado; nunca se guarda ni se muestra su cuerpo.
- Dos interruptores solo para desarrollo y pruebas, apagados por defecto: `einvoice.webhooks.allow-insecure-http` y `einvoice.webhooks.allow-private-addresses`.
- **El secreto** lo genera el servidor (32 bytes aleatorios, `whsec_` + Base64), se muestra una sola vez (al crear y al rotar) y se guarda cifrado con `SecretCipher` (contexto: empresa y suscripción). No aparece en listados, logs, auditoría, outbox, RabbitMQ ni métricas. La URL tampoco va a logs, auditoría ni métricas: es habitual que lleve una ficha en la consulta.
- **Firma** según la convención *Standard Webhooks*: cabeceras `webhook-id` (el `eventId`, igual en todos los reintentos), `webhook-timestamp` (segundos Unix del intento) y `webhook-signature: v1,<Base64 de HMAC-SHA256(clave, id + "." + timestamp + "." + cuerpo)>`. Tras rotar, durante 24 h van dos firmas separadas por un espacio (secreto nuevo y anterior).
- **El cuerpo lleva solo identificadores y estado**: `eventId`, `eventType`, `occurredAt`, `tenantId` y `document` (`id`, `documentType`, `number`, `accessKey`, `state`, `authorizationNumber`, `authorizedAt`). Ni datos del cliente, ni importes, ni XML, ni mensajes del SRI.
- Gestionan las suscripciones `OWNER` y `ADMIN`. `tenantId` sale del contexto autenticado; lo de otra empresa responde `404`.
- Todo cambio en una suscripción deja evento de auditoría en la misma transacción (sin URL ni secreto).
- Las migraciones solo avanzan (`V13`). Textos de interfaz en español; código en inglés. `contracts/openapi.yaml` (v0.11.0) cambia en el mismo commit que la API y `frontend/src/api/schema.ts` se regenera.
- Comandos: backend `./mvnw -B verify` desde `/backend` (con `-Djacoco.skip=true` en ejecuciones parciales); frontend `npm run lint && npm run typecheck && npm test && npm run build` desde `/frontend`.

## Decisiones de diseño

- **Eventos:** `document.received` (pasa a `RECEIVED`), `document.authorized` (`AUTHORIZED`), `document.rejected` (`RETURNED` o `NOT_AUTHORIZED`; el campo `state` dice cuál) y `document.delivered` (un correo de entrega salió). `XSD_INVALID` y `SIGNATURE_FAILED` no se notifican: la especificación lista cuatro eventos.
- **Una tabla, sin tabla de eventos.** `webhook_deliveries` guarda una fila por evento y suscripción, con el mismo `event_id` para todas las suscripciones. El cuerpo se arma al enviar con los datos del comprobante (que no cambian) y el estado que se anunció.
- **Reintentos:** 2xx es éxito; cualquier otra respuesta, un corte o un tiempo agotado se reintenta con espera creciente (`RetryPolicy`): 30 s de base, tope de 6 h, 10 intentos; después queda `FAILED` y se puede reintentar a mano. Conexión 5 s, total 10 s por intento.
- **Una suscripción que solo falla se desactiva sola** tras 100 intentos fallidos seguidos (`disable-after-failures`); un éxito pone la cuenta a cero. Reactivarla es un cambio del cliente.
- **Sin evento de prueba** ("ping"): las notificaciones se procesan por comprobante. Para probar hace falta emitir en el ambiente de pruebas.
- **Borrar una suscripción borra su historial de notificaciones**; queda el evento de auditoría.

## Review Focus

1. **Usar la aplicación para llegar a la red interna** (dirección privada, nombre que resuelve a una privada, nombre que cambia entre el registro y el envío, redirección a una interna, IP escrita en decimal u octal, IPv4 mapeada en IPv6) → nunca se conecta (Task 2).
2. **Un receptor lento, mudo o que responde sin fin** → cada intento acaba en el plazo, sin leer más que la línea de estado, y no retrasa los comprobantes ni el correo (Task 2).
3. **Fugas del secreto o de la URL** (listados, logs, auditoría, outbox, mensajes, métricas, errores) → no aparecen; el secreto en la base está cifrado (Task 1 y Task 2).
4. **El mismo evento consumido dos veces o por dos workers, o un proceso que muere tras enviar** → un solo envío por intento; si se repite, lleva el mismo `webhook-id` (Task 2).
5. **Suscripción borrada, desactivada o de otra empresa con notificaciones pendientes** → no se envían y no quedan en bucle; nadie ve ni reintenta las de otra empresa (Task 2 y Task 3).

---

### Task 1: Suscripciones (API)

**Files:** Create `backend/src/main/resources/db/migration/V13__webhooks.sql`; `backend/src/main/java/com/example/einvoice/webhook/{WebhookProperties,WebhookUrlPolicy,WebhookUrlException,WebhookSigner,WebhookSubscriptionService,WebhookController}.java`. Modify `contracts/openapi.yaml`, `backend/src/main/resources/application.yml`, `AbstractIntegrationTest` (webhooks encendidos y los dos interruptores de pruebas). Test: `webhook/WebhookUrlPolicyTest`, `WebhookSignerTest`, `WebhookSubscriptionApiIT`, `WebhookStrictPolicyIT`, `WebhooksDisabledIT`.

**Interfaces (Produces):**
- Tablas `webhook_subscriptions (id, tenant_id, url, event_types text[], description, status ACTIVE|DISABLED, disabled_reason, secret bytea, previous_secret bytea, previous_secret_expires_at, consecutive_failures, created_by, created_at)` y `webhook_deliveries (id, tenant_id, subscription_id, event_id, event_type, document_id, document_state, occurred_at, status PENDING|SENDING|SENT|FAILED, attempt_count, next_attempt_at, retry_after, sending_since, last_status, last_error, sent_at)`, única por `(subscription_id, event_id)`, borrado en cascada con la suscripción.
- `WebhookProperties(boolean enabled, boolean allowInsecureHttp, boolean allowPrivateAddresses, int maxSubscriptions, Duration retryBackoff, Duration retryMaxBackoff, int retryMaxAttempts, Duration sendLease, Duration connectTimeout, Duration totalTimeout, int disableAfterFailures, Duration secretOverlap)`; valores: `false, false, false, 10, 30s, 6h, 10, 2m, 5s, 10s, 100, 24h`.
- `WebhookUrlPolicy.parse(String url): URI` y `vet(URI): InetAddress` (resuelve y devuelve una dirección pública o lanza `WebhookUrlException` con el motivo); `static boolean isPublic(InetAddress)`. El resolutor de nombres se inyecta.
- `WebhookSigner.newSecret(): String`, `key(String secret): byte[]`, `sign(byte[] key, String id, long timestamp, byte[] body): String` (devuelve `v1,<Base64>`).
- `EVENT_TYPES = [document.received, document.authorized, document.rejected, document.delivered]`.
- Rutas (`/api/v1/webhooks/subscriptions`): `POST` → 201 con `secret`; `GET` → lista sin secreto; `PUT /{id}` (`url`, `eventTypes`, `description`, `enabled`) → 200; `DELETE /{id}` → 204; `POST /{id}/secret` → 200 `{secret, previousSecretExpiresAt}`.
- Errores: `409 WEBHOOKS_DISABLED`, `422 WEBHOOK_URL_NOT_ALLOWED`, `409 WEBHOOK_LIMIT_REACHED`. Auditoría: `WEBHOOK_CREATED`, `WEBHOOK_UPDATED`, `WEBHOOK_DELETED`, `WEBHOOK_SECRET_ROTATED`.

- [ ] **Step 1: pruebas que fallan.** `WebhookUrlPolicyTest`: acepta `https://hooks.example.com/a?b=c` y un puerto distinto; rechaza `http://` (salvo con el interruptor), `ftp://`, URL con usuario, con fragmento, sin nombre, de más de 2048 caracteres; `isPublic` falso para `127.0.0.1`, `10.1.2.3`, `172.16.0.1`, `172.31.255.255`, `192.168.1.1`, `169.254.169.254`, `100.64.0.1`, `0.0.0.0`, `224.0.0.1`, `255.255.255.255`, `192.0.0.1`, `198.18.0.1`, `::1`, `::`, `fe80::1`, `fc00::1`, `fd12::1`, `ff02::1`, `::ffff:10.0.0.1`, `::ffff:127.0.0.1`, `64:ff9b::a00:1`, y verdadero para `8.8.8.8`, `172.32.0.1`, `2606:4700::1111`; `vet` rechaza un nombre que resuelve a una pública y a una privada, y uno que no resuelve; `http://2130706433/` y `http://0177.0.0.1/` no se dan por públicas. `WebhookSignerTest`: vector calculado aparte (Python `hmac`), secretos distintos en cada llamada con prefijo `whsec_` y 32 bytes, firma distinta si cambia id, tiempo o cuerpo. `WebhookSubscriptionApiIT`: crear devuelve el secreto y la lista no; el secreto no está en claro en la base; roles (BILLER y AUDITOR 403); tipos de evento desconocidos o lista vacía 400; tope de 10 → 409; actualizar cambia URL, eventos y estado, y reactivar pone a cero `consecutiveFailures`; rotar devuelve un secreto nuevo y deja el anterior con caducidad a 24 h; borrar → 204 y luego 404; otra empresa no ve, cambia, rota ni borra (404); auditoría con los cuatro eventos y sin URL ni secreto en `metadata`. `WebhookStrictPolicyIT` (interruptores apagados): `http://`, `https://localhost/x` y `https://10.0.0.1/x` → 422 `WEBHOOK_URL_NOT_ALLOWED`. `WebhooksDisabledIT` (función apagada): crear → 409; listar → 200.
- [ ] **Step 2:** ejecutar y ver que fallan (no existe nada).
- [ ] **Step 3:** implementar migración, propiedades, política, firma, servicio, controlador y contrato.
- [ ] **Step 4:** `./mvnw -B verify -Dtest='Webhook*Test' -Dit.test='Webhook*IT,OpenApiContractIT' -Djacoco.skip=true` → verde.
- [ ] **Step 5:** commit `feat: suscripciones de webhooks (API)`.

### Task 2: Envío de notificaciones

**Files:** Create `webhook/{WebhookRecorder,WebhookRepository,WebhookSender,WebhookDeliveryService,WebhookWorker,DueWebhookScheduler}.java`, `support/WebhookReceiverStub.java` (pruebas). Modify `orchestration/{DocumentPipeline,MessagingConfig,WorkerSchedule}.java`, `delivery/{DeliveryService,DeliveryRepository}.java`. Test: `webhook/WebhookSenderTest`, `WebhookDeliveryIT`, `DueWebhookSchedulerIT`, `WebhookRoutingIT`.

**Interfaces:**
- Consumes: Task 1 completo; `RetryPolicy.delay`; `OutboxWriter.write`; `SecretCipher`.
- Produces: `WebhookRecorder.stateReached(UUID tenantId, UUID documentId, String state)` y `delivered(UUID tenantId, UUID documentId)` (exigen transacción; no hacen nada si la función está apagada); `WebhookSender.send(URI url, InetAddress address, Map<String,String> headers, byte[] body): Result(int status, String error)` con `Result.delivered()`; `WebhookDeliveryService.process(UUID tenantId, UUID documentId): int` (enviadas); cola `webhook.deliver` con su DLQ `webhook.deliver.dlq`; evento del outbox `WEBHOOK_DUE`; `DueWebhookScheduler.enqueueDue(): int`.

- [ ] **Step 1: pruebas que fallan.** `WebhookSenderTest` (servidores locales): entrega cuerpo y cabeceras tal cual con `Host` correcto; 204 y 200 entregan, 500 y 302 no (y la redirección no se sigue); un servidor que no responde termina en el plazo total; uno que responde con un cuerpo sin fin no se lee; un corte de conexión se informa sin excepción; se conecta a la dirección que se le pasa aunque el nombre de la URL no resuelva; con TLS, un certificado no confiable o de otro nombre falla y uno confiable para el nombre entrega (fábrica de sockets inyectada). `WebhookDeliveryIT`: una factura autorizada produce `document.received` y `document.authorized` en el receptor, con firma válida, `webhook-id` igual al `eventId` del cuerpo, y sin datos del cliente ni importes; una devuelta y una no autorizada, `document.rejected` con su `state`; un correo enviado, `document.delivered`; una suscripción solo recibe sus tipos; sin suscripciones no se anota nada; 500 → queda pendiente con espera y el reintento lleva el mismo `webhook-id`; agotados los intentos → `FAILED`; tras rotar van dos firmas y pasada la caducidad una; una suscripción desactivada o borrada no recibe y sus pendientes no se envían; 100 fallos seguidos la desactivan y un éxito pone la cuenta a cero; procesar dos veces el mismo comprobante no repite un envío hecho; una dirección que al enviar ya no es pública no se contacta y la notificación falla sin reintento; ni el secreto ni la URL aparecen en `outbox_events` ni en `audit_events`. `DueWebhookSchedulerIT`: encola una vez por comprobante con notificaciones vencidas y recupera un envío abandonado tras el plazo. `WebhookRoutingIT` (worker encendido): de emitir a recibir la notificación sin llamar nada a mano; `WEBHOOK_DUE` va a `webhook.deliver`.
- [ ] **Step 2:** ejecutar y ver que fallan.
- [ ] **Step 3:** implementar. El emisor abre un socket a la dirección comprobada, negocia TLS con SNI y verificación del nombre, escribe la petición HTTP/1.1 con `Connection: close` y lee como mucho la línea de estado dentro del plazo total. El servicio reserva cada notificación antes de enviar (misma mecánica que `DeliveryRepository.claim`) y anota el resultado fuera de transacción.
- [ ] **Step 4:** `./mvnw -B verify -Dtest='Webhook*Test' -Dit.test='Webhook*IT,DueWebhookSchedulerIT,Delivery*IT,ReceptionPipelineIT,AuthorizationPipelineIT' -Djacoco.skip=true` → verde.
- [ ] **Step 5:** commit `feat: envío de webhooks firmados con reintentos`.

### Task 3: Historial, reintento manual y operación

**Files:** Modify `webhook/{WebhookController,WebhookRepository,WebhookDeliveryService}.java`, `observability/OperationalMetrics.java`, `infra/observability/prometheus-alerts.yml`, `contracts/openapi.yaml`. Test: `webhook/WebhookDeliveryApiIT`, añadidos a `OperationalMetricsIT`.

**Interfaces (Produces):** `GET /api/v1/webhooks/subscriptions/{id}/deliveries?page=&size=` → página de `{id, eventId, eventType, documentId, status, attemptCount, lastStatus, lastError, occurredAt, sentAt, nextAttemptAt}` (más recientes primero); `POST /api/v1/webhooks/deliveries/{id}/retry` → 202 (solo `FAILED`; si no, `409 WEBHOOK_DELIVERY_NOT_RETRYABLE`; con la suscripción desactivada, `409 WEBHOOK_SUBSCRIPTION_DISABLED`). Métricas `einvoice.webhooks{status}` (sin `SENT`) y `einvoice.webhooks.disabled` (suscripciones desactivadas por fallos); la cola `webhook.deliver` y su DLQ entran en `einvoice.queue.messages`. Alertas `WebhooksFallidos` y `SuscripcionesDesactivadas`.

- [ ] **Step 1: pruebas que fallan.** `WebhookDeliveryApiIT`: el historial lista las notificaciones de la suscripción, paginado y sin las de otra; reintentar una fallida la deja pendiente con los intentos a cero y sale en el siguiente proceso; reintentar una enviada o pendiente → 409; roles; otra empresa → 404 en ambas rutas. `OperationalMetricsIT`: las métricas nuevas reflejan pendientes, fallidas y suscripciones desactivadas.
- [ ] **Step 2:** ejecutar y ver que fallan. **Step 3:** implementar. **Step 4:** `-Dit.test='Webhook*IT,OperationalMetricsIT,OpenApiContractIT'` → verde. **Step 5:** commit `feat: historial y reintento de webhooks, métricas`.

### Task 4: Pantalla "Integraciones"

**Files:** Create `frontend/src/app/(app)/integraciones/page.tsx` (+ `page.test.tsx`). Modify `frontend/src/components/AppShell.tsx`, `frontend/src/api/problem.ts`, `frontend/src/api/schema.ts` (regenerado).

- [ ] **Step 1: pruebas que fallan.** La pantalla lista las suscripciones con sus eventos y estado; crear muestra el secreto una sola vez, con el aviso de que no se volverá a ver, y la lista se actualiza; desactivar y reactivar; rotar pide confirmación y muestra el secreto nuevo; borrar pide confirmación; el historial de una suscripción muestra sus notificaciones y permite reintentar una fallida; una desactivada por fallos lo dice; los errores de la API (`WEBHOOK_URL_NOT_ALLOWED`, `WEBHOOKS_DISABLED`, `WEBHOOK_LIMIT_REACHED`) se muestran en claro; quien no es propietario ni administrador no ve la pantalla ni el enlace del menú.
- [ ] **Step 2:** ver que fallan. **Step 3:** implementar con los componentes y estilos existentes; incluir cómo verificar la firma en tres líneas. **Step 4:** `npm run lint && npm run typecheck && npm test && npm run build` → verde. **Step 5:** commit `feat(frontend): integraciones (webhooks)`.

### Task 5: Documentación

**Files:** `README.md`, `docs/04-database-design.md`, `docs/05-electronic-document-flow.md`, `docs/10-security.md`, `docs/11-api-specification.md`, `docs/13-deployment.md`, `docs/15-produccion.md`, `frontend/README.md`, `.env.example`, `infra/docker-compose.stack.yml`, plan maestro.

- [ ] Describir lo implementado y sus límites (formato del evento, cómo verificar la firma con un ejemplo, reintentos, desactivación, qué direcciones se rechazan y por qué, que viene apagado), quitar las rutas de "pendientes" que ya existen, y dejar P6b como implementado. Suite completa en verde. Commit `docs: webhooks`.

---

## Cambios tras la revisión final

La revisión independiente de la rama corrigió varias decisiones de arriba. Lo vigente es esto:

- **Reintentos:** 14 intentos, no 10; con la espera que se duplica desde 30 s, diez intentos cubrían unas 4 horas, no "alrededor de un día".
- **Apagado automático:** exige 100 intentos fallidos seguidos **y** más de 3 días fallando (`disable-after-failing-for`); contado solo en intentos, una caída de minutos en una empresa con muchos comprobantes la apagaba.
- **Dirección que deja de ser pública al enviar:** no se conecta, pero se reintenta como cualquier otro fallo (antes fallaba sin reintento).
- **Apagar una suscripción** no vuelve a comprobar su dirección; solo se comprueba la que cambia o la que se enciende, y siempre fuera de la transacción.
- **Fallos propios** (no poder firmar, error imprevisto) se reintentan sin contar contra el receptor.
- La dirección debe ir en ASCII y su nombre no puede acabar en punto.
- Solo quedan dos alertas (`WebhooksFallidos`, con umbral, y `SuscripcionesDesactivadas`).
