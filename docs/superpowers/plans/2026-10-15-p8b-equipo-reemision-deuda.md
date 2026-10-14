# Plan 8b — Equipo de la empresa, reemisión de rechazados y deuda de revisiones

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que una empresa pueda trabajar con más de una persona (invitar, cambiar de rol, retirar), que un comprobante rechazado por el SRI tenga salida, y cerrar lo que las revisiones de los planes 7 y 8 dejaron apuntado y pesa en producción.

**Architecture:** todo sobre lo existente. Las invitaciones son un enlace de un solo uso, ligado a un correo y con caducidad, del que la base guarda solo la huella. La reemisión no toca el comprobante rechazado (sigue siendo inmutable): crea uno nuevo que lo reemplaza y deja el vínculo en ambos. La deuda son correcciones acotadas, cada una con su prueba.

**Tech Stack:** el de los planes anteriores; nada nuevo.

**Spec:** [plan maestro](2026-10-10-plan-maestro.md) ("Decisiones abiertas": invitación de usuarios; P5: "`RETURNED` y `NOT_AUTHORIZED` no tienen aún flujo de corrección y reenvío"), [doc 10](../../10-security.md), [doc 04](../../04-database-design.md), [doc 05](../../05-electronic-document-flow.md), y los "Hallazgos menores sin corregir" de las revisiones de los planes 7 y 8 ([doc 15](../../15-produccion.md)).

**Por qué no el Plan 9:** los demás comprobantes (nota de crédito, retención, guía) necesitan sus XSD oficiales y el repositorio solo tiene los de factura. Sin ellos no se empieza.

## Global Constraints

- `tenantId` sale siempre del contexto autenticado; un recurso de otra empresa responde igual que uno inexistente (`404`).
- El código de una invitación no se guarda: solo su SHA-256. No aparece en logs, auditoría, métricas ni mensajes de error; la API lo devuelve una única vez, al crearla.
- Nadie concede un rol superior al suyo. El rol `OWNER` no se concede, no se cambia y no se retira por estas operaciones: una empresa nunca se queda sin propietario.
- Todo cambio de membresía o invitación deja evento de auditoría en la misma transacción.
- Un comprobante emitido no se edita ni se borra: reemitir crea otro.
- Las migraciones solo avanzan (`V10`…).
- Textos de interfaz en español; código en inglés. `contracts/openapi.yaml` cambia en el mismo commit que la API y `frontend/src/api/schema.ts` se regenera.
- Comandos: backend `./mvnw -B verify` desde `/backend` (con `-Djacoco.skip=true` en ejecuciones parciales); frontend `npm run lint && npm run typecheck && npm test && npm run build` desde `/frontend`.

## Decisiones de diseño

- **Invitación ligada a un correo.** Para aceptarla, el token de quien inició sesión debe traer ese correo, y no marcado como sin verificar. Si el proveedor de identidad no pone el correo en el token de acceso, la aceptación se rechaza, salvo que se habilite `einvoice.invitations.allow-without-email` (el enlace pasa entonces a valer para quien lo tenga).
- **El enlace lo comparte quien invita.** La aplicación no envía correos de invitación: el correo viene apagado por defecto y no debe ser condición para sumar a alguien.
- **Roles que se pueden conceder:** el propietario, `ADMIN`, `BILLER` y `AUDITOR`; un administrador, `BILLER` y `AUDITOR`. La transferencia de la propiedad queda fuera.
- **Retirar es borrar la membresía.** El historial (quién emitió qué) sigue apuntando al usuario.
- **Reemitir es emitir de nuevo.** Con secuencial y clave nuevos; nunca se reutiliza la clave de un comprobante rechazado. El rechazado queda como está, enlazado con el que lo reemplaza.

## Review Focus

1. **Abusar de una invitación** (usarla dos veces, a la vez, caducada, revocada, con otro correo, adivinar el código, invitar a quien ya es miembro, conceder un rol superior al propio, ver o revocar las de otra empresa) → rechazado, sin revelar si el código existió (Task 1).
2. **Escalar privilegios en el equipo** (un administrador cambiando roles, alguien cambiando el suyo, retirar o degradar al propietario, actuar sobre un usuario de otra empresa) → rechazado (Task 1).
3. **Quien es retirado deja de tener acceso en su siguiente petición**, y la web lo lleva a elegir empresa en vez de quedarse en un error (Task 1 y Task 2).
4. **Reemitir lo que no se debe** (un comprobante autorizado o en curso, el de otra empresa, el mismo dos veces) → rechazado; el rechazado no cambia (Task 3).
5. **Auditoría de solo inserción** → ningún flujo de la aplicación deja de funcionar, y un respaldo se restaura igual (Task 4).

---

### Task 1: Equipo e invitaciones (API)

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__team_invitations.sql`, `…/tenancy/TeamService.java`, `TeamController.java`, `InvitationService.java`, `InvitationController.java`, `InvitationProperties.java`; pruebas `…/tenancy/TeamApiIT.java`, `InvitationApiIT.java`
- Modify: `…/tenancy/Role.java`, `MembershipRepository.java`, `…/identity/UserIdentity.java` y `UserIdentityResolver.java` (correo verificado), `application.yml`, `contracts/openapi.yaml` (0.9.0), `frontend/src/api/schema.ts`

**Interfaces:**
- Produces: `GET /members` → `Member[] { userId, email, role, since, self }` (OWNER, ADMIN). `PUT /members/{userId}/role` `{ role }` → `Member` (solo OWNER). `DELETE /members/{userId}` → `204`.
- Produces: `POST /invitations` `{ email, role }` → `201 CreatedInvitation { id, email, role, expiresAt, token }`. `GET /invitations` → `Invitation[] { id, email, role, expiresAt, invitedBy }` (pendientes y vigentes). `DELETE /invitations/{id}` → `204`.
- Produces: `POST /invitations/preview` `{ token }` → `{ tenantName, role, email, expiresAt }` y `POST /invitations/accept` `{ token }` → `Tenant`. Ambas con sesión pero sin empresa activa.
- Produces: códigos `INVITATION_NOT_VALID` (404), `INVITATION_EMAIL_MISMATCH` (403), `EMAIL_NOT_VERIFIED` (403), `ALREADY_MEMBER` (409), `ROLE_NOT_GRANTABLE` (403), `OWNER_PROTECTED` (409), `INVITATION_LIMIT_REACHED` (409).
- Produces: propiedades `einvoice.invitations.ttl` (7d), `max-pending` (50), `allow-without-email` (false).

- [ ] **Step 1:** `InvitationApiIT`, todo fallando: el propietario invita a un `BILLER` y recibe un código de al menos 40 caracteres que no está en la base (solo su huella), ni en la auditoría, ni en la lista de pendientes; el invitado previsualiza (nombre de la empresa, rol) y acepta: queda como miembro con ese rol, la invitación deja de estar pendiente y hay eventos `INVITATION_CREATED` e `INVITATION_ACCEPTED`; aceptar otra vez → 404; dos aceptaciones simultáneas del mismo código → una sola membresía; caducada → 404; revocada → 404; código inventado → 404 con el mismo cuerpo; correo distinto → 403 `INVITATION_EMAIL_MISMATCH` y la invitación sigue vigente; token con `email_verified: false` → 403; token sin correo → 403, y con `allow-without-email` → acepta; quien ya es miembro → 409; un administrador invita a `BILLER` pero no a `ADMIN` (403); `OWNER` no se puede invitar (400 o 403); `BILLER` y `AUDITOR` no invitan (403); invitar dos veces al mismo correo deja una sola pendiente (la nueva) y el código anterior deja de valer; la 51.ª pendiente → 409; otra empresa no ve ni revoca las ajenas (404).
- [ ] **Step 2:** `TeamApiIT`: lista con correos y `self`; `BILLER` no la ve (403); el propietario cambia un rol y queda auditado; un administrador no cambia roles (403); nadie cambia el suyo ni el del propietario (409 `OWNER_PROTECTED` o 403); el propietario retira a un miembro y su siguiente petición con esa empresa responde 403 `TENANT_FORBIDDEN`; un administrador retira a un `BILLER` pero no a otro administrador (403); el propietario no puede retirarse (409); usuario de otra empresa → 404.
- [ ] **Step 3:** correr ambas y verlas fallar.
- [ ] **Step 4:** migración (`invitations` con `token_hash` único y un índice único parcial por empresa y correo para las pendientes), servicios y controladores, `Role.canGrant(Role)`.
- [ ] **Step 5:** contrato 0.9.0 y `npm run api:generate`.
- [ ] **Step 6:** suite de backend en verde; commit `feat: equipo de la empresa e invitaciones`.

### Task 2: Equipo e invitaciones (web)

**Files:**
- Create: `frontend/src/app/(app)/equipo/page.tsx` (+ prueba), `frontend/src/app/(app)/invitacion/page.tsx` (+ prueba)
- Modify: `src/server/proxy.ts` (admite `DELETE`) y su prueba, `src/components/AppShell.tsx`, `src/api/problem.ts`, `src/lib/tenantContext.tsx`, `src/server/config.ts` (ámbito `email` por defecto), `infra/keycloak/einvoice-realm.json` si hace falta para que el token traiga el correo

- [ ] **Step 1:** pruebas que fallan. Proxy: `DELETE` se reenvía con las mismas exigencias que `POST` (mismo origen y marca) y sin ellas → 403. Equipo: lista miembros y pendientes; invitar muestra el enlace completo (`…/invitacion?codigo=…`) una vez, con un botón para copiarlo, y avisa de que no se volverá a mostrar; cambiar rol y retirar piden confirmación y llaman a la API; un administrador no ve el selector de rol ni puede retirar administradores; `BILLER` no ve la pantalla en el menú y, si entra, se le dice que su rol no lo permite. Invitación: muestra empresa y rol antes de aceptar; al aceptar deja esa empresa activa y va a comprobantes; código no válido o correo distinto → mensaje claro; sin código → explica cómo se usa.
- [ ] **Step 2:** implementación. Si una petición responde `403 TENANT_FORBIDDEN` con una empresa activa, el contexto recarga las empresas y, si esa ya no está, lleva a "Empresas".
- [ ] **Step 3:** comprobación en el navegador integrado contra la API real y el Keycloak local, con dos usuarios de prueba temporales: invitar, aceptar con el segundo, verlo en el equipo, retirarlo y comprobar que pierde el acceso. Confirmar de paso que el token de acceso trae el correo.
- [ ] **Step 4:** suite en verde; commit `feat(frontend): equipo e invitaciones`.

### Task 3: Reemitir un comprobante rechazado

**Files:**
- Create: `V11__document_replacement.sql`, prueba `…/billing/InvoiceReissueIT.java`
- Modify: `…/billing/InvoiceController.java`, `InvoiceService.java`, `FiscalDocumentRepository.java`, `DocumentController.java`, `contracts/openapi.yaml`, `frontend/src/api/schema.ts`, `frontend/src/app/(app)/documentos/[id]/page.tsx`, `frontend/src/app/(app)/facturas/nueva/page.tsx`, `frontend/src/lib/drafts.ts`, y sus pruebas

**Interfaces:**
- Produces: `IssueInvoiceRequest.replacesDocumentId` (opcional). `Document` gana `customerId`, `lines[].productId`, `replacesDocumentId` y `replacedByDocumentId`.
- Produces: códigos `REPLACED_DOCUMENT_NOT_REJECTED` (409) y `DOCUMENT_ALREADY_REPLACED` (409); un comprobante ajeno o inexistente → 404.
- Produces: `Draft.replacesDocumentId?: string`.

- [ ] **Step 1:** `InvoiceReissueIT`, fallando: emitir con `replacesDocumentId` de un comprobante `RETURNED` o `NOT_AUTHORIZED` crea uno nuevo con secuencial y clave propios; el detalle del nuevo dice a cuál reemplaza y el del rechazado cuál lo reemplazó; el rechazado conserva estado, clave y artefactos; reemplazar uno `AUTHORIZED`, `SIGNED` o `RECEIVED` → 409; reemplazar dos veces el mismo → 409; el de otra empresa → 404; repetir la misma petición con la misma clave de idempotencia devuelve la misma factura; hay evento `DOCUMENT_REPLACED`.
- [ ] **Step 2:** migración (columna con clave foránea e índice único parcial), servicio y vistas.
- [ ] **Step 3:** web, con pruebas primero: en el detalle de un comprobante devuelto o no autorizado, "Corregir y emitir de nuevo" crea un borrador con su cliente y sus líneas y abre el editor, que avisa de a cuál reemplaza y lo envía al emitir; el detalle enlaza ambos comprobantes; un comprobante ya reemplazado no ofrece la acción.
- [ ] **Step 4:** suites en verde; commit `feat: reemisión de comprobantes rechazados`.

### Task 4: Deuda de las revisiones (API y operación)

**Files:**
- Create: `V12__state_time_and_append_only_audit.sql`, prueba `…/observability/MetricsOverHttpIT.java`, `…/compliance/AuditImmutabilityIT.java`
- Modify: `…/observability/OperationalMetrics.java`, `MetricsTokenFilter.java`, `MetricsSecurityConfig.java`, `…/billing/FiscalDocumentRepository.java`, `infra/backup/backup.sh`, `restore.sh`, `infra/postgres/roles.sql`, `…/recovery/RecoveryIT.java`, y pruebas existentes

- [ ] **Step 1:** pruebas que fallan: con la lectura de la base fallando, los medidores conservan su último valor; sobre HTTP real (puerto aleatorio), sin ficha configurada `/actuator/prometheus` responde 404 y con ficha y sin cabecera 401; la protección sigue aplicando si se cambia `management.endpoints.web.base-path`; `einvoice_documents_oldest_seconds` mide desde que el comprobante entró en su estado, no desde que se creó; `UPDATE`, `DELETE` y `TRUNCATE` sobre `audit_events` y `state_events` se rechazan; `RecoveryIT` compara también `audit_events`; un volcado restaurado con una huella que no coincide, o sin disparadores, hace fallar a `restore.sh`.
- [ ] **Step 2:** implementación: columna `state_changed_at` con sus datos iniciales tomados de `state_events`; disparadores de solo inserción; `EndpointRequest` para la cadena de métricas; scripts con `umask 077`, contraseña por entorno y salida distinta de cero ante huellas distintas.
- [ ] **Step 3:** suite en verde; commit `fix: deuda de las revisiones en métricas, auditoría y respaldo`.

### Task 5: Deuda de las revisiones (web)

**Files:**
- Modify: `frontend/src/server/sessionStore.ts`, `src/app/api/backend/[...path]/route.ts`, `src/lib/drafts.ts`, `src/app/(app)/facturas/nueva/page.tsx`, y sus pruebas

- [ ] **Step 1:** pruebas que fallan: el evento `session.refresh.unavailable` lleva su campo de vigencia (hoy se pierde por llamarse `tokenStillValid`); una respuesta cuyo cuerpo tarda más que el plazo de la API en llegar entera no se corta (el plazo es hasta las cabeceras); al llegar al máximo de borradores, guardar uno nuevo se rechaza y el editor lo dice, en vez de borrar el más antiguo.
- [ ] **Step 2:** implementación.
- [ ] **Step 3:** suite en verde; commit `fix(frontend): deuda de las revisiones`.

### Task 6: Documentación

**Files:**
- Modify: `README.md`, `docs/10-security.md`, `docs/11-api-specification.md`, `docs/13-deployment.md`, `docs/15-produccion.md`, `docs/DEVELOPMENT.md`, `frontend/README.md`, `docs/superpowers/plans/2026-10-10-plan-maestro.md`

- [ ] **Step 1:** equipo e invitaciones (cómo funcionan, qué exige del proveedor de identidad, sus límites), reemisión, lo corregido de la deuda y lo que sigue pendiente; fila del plan en el plan maestro, con el Plan 9 marcado como bloqueado por los XSD.
- [ ] **Step 2:** suites completas en verde; commit `docs: equipo, reemisión y deuda`.
