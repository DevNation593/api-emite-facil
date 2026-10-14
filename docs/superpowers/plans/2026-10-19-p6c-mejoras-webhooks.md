# Plan 6c — Mejoras de los webhooks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** cerrar lo que el Plan 6b dejó pendiente en los avisos a sistemas de clientes ([doc 15](../../15-produccion.md), "Lo que sigue abierto"): que una empresa pueda probar su dirección sin emitir una factura, que el historial no crezca sin fin y que un receptor lento de una empresa no frene los avisos de las demás.

**Architecture:** tres cambios en el módulo `webhook` y uno en la pantalla "Integraciones":

- un **aviso de prueba** (`webhook.test`), que es un aviso más (firmado, por la misma cola, en el historial) pero sin comprobante y con un solo intento;
- una **purga periódica** del historial en el worker;
- un **tope de envíos simultáneos por empresa**, que se comprueba al reservar cada aviso.

**Tech Stack:** el de los planes anteriores; nada nuevo.

**Repositorios:** API en `api-emite-facil` y pantalla en `client-factura-facil`. Una rama del mismo nombre en cada uno (`p6c-mejoras-webhooks`), para que el CI de la API pruebe con la web de esa rama. PR a `dev` en los dos.

**Spec:** [doc 11](../../11-api-specification.md) ("Webhooks salientes"), [doc 10](../../10-security.md), [Plan 6b](2026-10-16-p6b-webhooks.md) (decisiones que siguen valiendo).

## Global Constraints

- Todo lo del Plan 6b sigue en pie:
  - apagado por defecto;
  - solo `https` a direcciones públicas, comprobadas en cada envío;
  - firma *Standard Webhooks*;
  - ni la dirección ni el secreto van a logs, auditoría, outbox, RabbitMQ ni métricas;
  - el cuerpo no lleva datos del cliente ni importes.
- **El aviso de prueba lo piden `OWNER` y `ADMIN`**, solo sobre una suscripción activa de su empresa y con los avisos encendidos en la instalación. Deja evento de auditoría (`webhook.test_requested`, sin dirección).
- **Migración `V14`** (solo avanza). El contrato pasa a `0.12.0` en el mismo commit que la API. La web copia el contrato y regenera `src/api/schema.ts`.
- **Comandos:**
  - API: `./mvnw -B verify` (con `-Djacoco.skip=true` en ejecuciones parciales).
  - Web: `npm run lint && npm run typecheck && npm test && npm run build`.
  - Navegador: `npm run stack:up && npm run e2e` en `e2e/`, con la web de la misma rama clonada al lado.

## Decisiones de diseño

- **El aviso de prueba:**
  - Tiene `eventType` `webhook.test` y su cuerpo es `{eventId, eventType, occurredAt, tenantId, subscriptionId}`, sin `document`.
  - Sale por la misma cola y con la misma firma que los demás, y aparece en el historial como "Aviso de prueba".
  - Se intenta **una vez**: no se reintenta y no cuenta para apagar la suscripción ni para borrar sus fallos. Es para probar ahora, no para insistir.
  - Mientras haya uno pendiente o en curso en esa suscripción, pedir otro responde `409 WEBHOOK_TEST_PENDING`. Así nadie usa la aplicación para lanzar peticiones en ráfaga contra una dirección.
- **En la base**:
  - `document_id` y `document_state` admiten nulo, y solo un `webhook.test` puede no tener comprobante (restricción `CHECK`).
  - El tipo de evento de las suscripciones no cambia: nadie se suscribe a `webhook.test`.
- **En la cola:**
  - Un aviso de prueba viaja como evento `WEBHOOK_TEST` del outbox, con la suscripción como agregado.
  - El worker lo distingue por el tipo de evento.
  - El planificador de vencidos manda `WEBHOOK_TEST` para los avisos sin comprobante y `WEBHOOK_DUE` para los demás.
- **Reparto entre empresas:**
  - Como mucho `einvoice.webhooks.max-concurrent-per-tenant` envíos en curso por empresa (por defecto 2).
  - Reservar un aviso toma un candado de transacción por empresa (`pg_advisory_xact_lock`) y cuenta los envíos en curso que no han vencido. La cuenta es una sentencia aparte, para que vea lo que otro proceso acaba de reservar.
  - Si la empresa está en el tope, el aviso no se reserva: se reprograma a `einvoice.webhooks.busy-delay` (5 s), sin gastar un intento, y el consumidor queda libre para otra empresa.
- **Purga:**
  - El worker borra cada hora, por lotes de 1000, los avisos `SENT` o `FAILED` con más de `einvoice.webhooks.history-retention` (90 días). Los pendientes y en curso no se tocan.
  - Borrar no deja auditoría: el historial de avisos es operativo, no fiscal.
- **Fuera de este plan:** el acceso de máquina a la API (para que un receptor consulte el detalle de un comprobante) necesita su propio diseño de credenciales y queda para otro plan.

## Review Focus

1. **Dos procesos reservan a la vez avisos de la misma empresa en el tope** → solo uno envía (Task 3).
2. **Una empresa con un receptor que tarda 10 s y otra con uno rápido** → los avisos de la segunda no esperan a los de la primera (Task 3).
3. **Pedir dos avisos de prueba seguidos** → el segundo responde 409 mientras el primero no ha terminado (Task 1).
4. **Un aviso de prueba que falla** → queda `FAILED` tras un intento, sin reintentos y sin sumar fallos a la suscripción (Task 1).
5. **La purga con avisos pendientes antiguos** (reintentos largos) → no los borra (Task 2).

---

### Task 1: Aviso de prueba (API)

**Files:**
- Create `src/main/resources/db/migration/V14__webhook_test_and_fairness.sql`.
- Modify `webhook/WebhookController.java`, `WebhookSubscriptionService.java`, `WebhookDeliveryService.java`, `WebhookRepository.java`, `WebhookWorker.java`, `DueWebhookScheduler.java`, `orchestration/MessagingConfig.java` (enrutado de `WEBHOOK_TEST`), `contracts/openapi.yaml`.
- Tests: `webhook/WebhookTestIT.java`.

**Interfaces — Produces:**
- `POST /api/v1/webhooks/subscriptions/{id}/test` → `202` con el `WebhookDelivery` creado.
- Errores: `409 WEBHOOKS_DISABLED`, `409 WEBHOOK_SUBSCRIPTION_DISABLED`, `409 WEBHOOK_TEST_PENDING` y `404`.
- `WebhookDeliveryService.processTests(UUID tenantId, UUID subscriptionId): int`.
- En el contrato, `WebhookDelivery.eventType` usa un esquema nuevo, `WebhookDeliveryEventType` (los cuatro de siempre más `webhook.test`), y `documentId` admite nulo.

- [x] **Step 1:** pruebas en `WebhookTestIT`:
  - Pedir una prueba sobre una suscripción activa → `202`. El receptor simulado recibe un `POST` firmado (la firma se verifica con el secreto) con `eventType` `webhook.test`, `subscriptionId` y sin `document`. El historial lo muestra `SENT`.
  - Un receptor que responde 500 → `FAILED` con `attemptCount` 1, sin reintento programado, y la suscripción con `consecutiveFailures` 0.
  - Una segunda prueba mientras la primera está `PENDING` → `409 WEBHOOK_TEST_PENDING`.
  - Una suscripción apagada → `409 WEBHOOK_SUBSCRIPTION_DISABLED`.
  - Avisos apagados en la instalación → `409 WEBHOOKS_DISABLED`.
  - Un `BILLER` → `403`.
  - La suscripción de otra empresa → `404`.
  - Queda un evento de auditoría sin la dirección.
- [x] **Step 2:** migración, endpoint, servicio, enrutado y contrato hasta que pasen; `OpenApiContractIT` en verde.
- [x] **Step 3:** commit `feat: aviso de prueba de webhooks`.

### Task 2: Purga del historial

**Files:** Create `webhook/WebhookHistoryPurge.java`; Modify `WebhookProperties.java`, `application.yml`; Test `webhook/WebhookHistoryPurgeIT.java`.

**Interfaces — Produces:** `WebhookHistoryPurge.purge(): int` (cuántos borró), programado cada `einvoice.webhooks.purge-interval-ms` (3 600 000) solo donde corre el worker.

- [x] **Step 1:** pruebas: con un retención de 90 días, avisos `SENT` y `FAILED` de hace 91 días se borran; uno `SENT` de hace 89 días y uno `PENDING` de hace 200 días quedan; con más de 1000 vencidos, `purge()` los borra en lotes hasta terminar.
- [x] **Step 2:** implementar. Commit `feat: purga del historial de webhooks`.

### Task 3: Reparto entre empresas

**Files:** Modify `WebhookRepository.claim`, `WebhookDeliveryService`, `WebhookProperties.java`, `application.yml`; Test `webhook/WebhookFairnessIT.java`.

**Interfaces — Produces:**
- `WebhookRepository.claim(UUID deliveryId, Duration lease, int maxPerTenant, Duration busyDelay): Optional<Claim>`, transaccional.
- `einvoice.webhooks.max-concurrent-per-tenant` (2) y `einvoice.webhooks.busy-delay` (5 s).

- [x] **Step 1:** pruebas:
  - Con dos avisos de la misma empresa ya `SENDING`, reservar un tercero no lo reserva: sigue `PENDING`, con el mismo `attemptCount` y `next_attempt_at` a unos 5 s.
  - Con dos de otra empresa `SENDING`, se reserva.
  - Un envío `SENDING` vencido no cuenta.
  - Ocho hilos que reservan a la vez avisos de una misma empresa → como mucho dos quedan `SENDING`.
  - Extremo a extremo con el receptor simulado: una empresa cuyo receptor tarda 3 s y otra con uno inmediato. Con diez avisos de la primera y uno de la segunda encolados a la vez, el de la segunda se entrega antes de que terminen los de la primera.
- [x] **Step 2:** implementar. Commit `feat: los avisos de una empresa no acaparan el envío`.

### Task 4: Pantalla "Integraciones" (web)

**Files (client-factura-facil):** `contracts/openapi.yaml`, `src/api/schema.ts` (regenerado), `src/app/(app)/integraciones/page.tsx` y su prueba.

- [x] **Step 1:** pruebas de componente:
  - Cada suscripción activa tiene "Enviar aviso de prueba"; al pulsarlo se llama al endpoint, se avisa "Aviso de prueba en camino" y se abre su historial.
  - En el historial, un `webhook.test` se lee "Aviso de prueba" y no tiene enlace a comprobante.
  - Un `409 WEBHOOK_TEST_PENDING` se explica con "Ya hay un aviso de prueba en camino".
- [x] **Step 2:** implementar. Commit `feat(web): aviso de prueba de webhooks`.

### Task 5: Navegador, documentación y PR

- [x] **Step 1:** `e2e/tests/integraciones.spec.ts`: enviar un aviso de prueba desde la pantalla. Llega firmado al receptor del simulador y el historial lo muestra entregado.
- [x] **Step 2:** documentación:
  - doc 11: endpoint y evento;
  - doc 10: límites del aviso de prueba;
  - doc 15: "Lo que sigue abierto" sin estos tres puntos y con el acceso de máquina;
  - plan maestro: fila P6c y decisión.
- [x] **Step 3:** API, web y navegador completos en verde. PR a `dev` en los dos repositorios.
