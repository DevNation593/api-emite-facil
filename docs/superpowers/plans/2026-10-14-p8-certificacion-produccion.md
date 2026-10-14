# Plan 8 — Certificación y producción

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dejar el sistema en condiciones de operar con emisores reales: nadie llega a producción sin evidencia, lo que pasa dentro se puede ver y recuperar, y la certificación ante el SRI se ejecuta con un solo comando en cuanto exista un certificado real.

**Architecture:** todo se añade al monolito y a la aplicación web existentes. La puerta a producción es un dato del emisor que la base impone; la observabilidad sale por Micrometer en un punto protegido por un token propio; la integridad y la recuperación se prueban contra PostgreSQL real con `pg_dump`/`pg_restore`; la matriz de certificación es un programa que corre igual contra el SRI simulado (siempre) y contra el SRI de pruebas (solo a petición).

**Tech Stack:** el de los planes anteriores, más `micrometer-registry-prometheus`, JaCoCo, CycloneDX (Maven) y Docker para las imágenes.

**Spec:** fila P8 del [plan maestro](2026-10-10-plan-maestro.md); [doc 10](../../10-security.md) (controles y checklist de preproducción), [doc 12](../../12-testing-certification.md) (matriz, casos sugeridos, gates, evidencias), [doc 13](../../13-deployment.md) (CI/CD, SLO, backup, alertas) y [DEVELOPMENT §9](../../DEVELOPMENT.md).

## Global Constraints

- Ninguna prueba transmite al SRI real salvo `SriCertificationIT`, que solo corre con el perfil Maven `certification` y variables de entorno explícitas.
- Nada nuevo habla con el exterior por defecto: métricas sin token configurado no existen (`404`), la certificación real no corre, el correo y el SRI siguen apagados.
- Secretos (`.p12`, contraseñas, tokens, cookies, clave maestra) nunca en logs, métricas, etiquetas, evidencias ni mensajes de error. Las etiquetas de métricas no identifican empresas ni personas.
- Las migraciones solo avanzan (`V7`, `V8`…); ninguna edita una anterior.
- Todo cambio de estado de negocio deja evento de auditoría en la misma transacción.
- Imágenes sin `latest`, con usuario no root. No se publica ninguna imagen ni se crea ningún remoto.
- No se descargan navegadores ni el ZIP de XSD del SRI.
- Textos de interfaz en español; código en inglés. El contrato `contracts/openapi.yaml` se actualiza en el mismo commit que cambia la API y `frontend/src/api/schema.ts` se regenera.
- Comandos: backend `./mvnw -B verify` desde `/backend`; frontend `npm run lint && npm run typecheck && npm test && npm run build` desde `/frontend`.

## Decisiones de diseño

- **Activación de producción en autoservicio con evidencia.** No existe un rol de operador de la plataforma. El propietario de la empresa activa producción para su emisor, y el sistema solo lo permite si hay un certificado vigente, al menos un comprobante `AUTHORIZED` por el SRI en el ambiente de pruebas y la declaración expresa de que el SRI habilitó ese RUC en producción. El proveedor conserva el interruptor global (`SRI_ENABLED_ENVIRONMENTS`).
- **Un emisor nace siempre en pruebas.** Crear con ambiente `2` se rechaza; la base impide el ambiente `2` sin activación.
- **Métricas con token propio**, no con el JWT de usuarios: quien recoge métricas no es un usuario de ninguna empresa.
- **Límite de peticiones por usuario** en Redis, con ventana fija de un minuto; si Redis no responde, la API sigue atendiendo (y lo cuenta en una métrica). Los abusos sin autenticar son tarea del proxy de entrada.
- **Roles de base separados** se entregan como script y se prueban, pero el desarrollo local sigue con un solo usuario.
- **Sin despliegue real:** no hay nube elegida. Se entregan imágenes, un compose de referencia para staging y el CI que las construye; publicar y desplegar quedan abiertos.

## Review Focus

1. **Llegar a producción sin evidencia** (crear emisor con `2`, activar sin certificado o sin comprobante autorizado en pruebas, activar sin ser propietario, activar el emisor de otra empresa, forzarlo por SQL) → rechazado en cada capa (Task 1).
2. **Leer métricas sin permiso o sacar datos por ellas** (sin token, token equivocado, token sin configurar; etiquetas con RUC, nombres o identificadores) → nada sale (Task 2).
3. **Restaurar un respaldo** → los artefactos conservan su huella, los disparadores de inmutabilidad siguen ahí y el siguiente secuencial no repite ninguno (Task 3).
4. **Redis caído o usuario abusivo** → la API sigue funcionando para todos; el límite es por usuario y responde `429` bien formado (Task 4).
5. **Política de contenido estricta** → las pantallas y el inicio y cierre de sesión siguen funcionando sin infracciones; fuera de local no se aceptan cookies sin `Secure` (Task 5).

---

### Task 1: Activación de producción por emisor

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__production_activation.sql`, `backend/src/main/java/com/example/einvoice/compliance/ProductionActivation.java`, `…/compliance/ProductionActivationController.java`, `backend/src/test/java/com/example/einvoice/compliance/ProductionActivationIT.java`
- Modify: `…/taxpayer/Taxpayer.java`, `TaxpayerService.java`, `TaxpayerController.java`, `…/tenancy/TenantAccess.java`, `…/orchestration/DocumentPipeline.java`, `contracts/openapi.yaml` (0.7.0), `frontend/src/api/schema.ts`, `frontend/src/api/problem.ts`, `frontend/src/app/(app)/configuracion/emisor/page.tsx` y su prueba

**Interfaces:**
- Produces: `GET /api/v1/taxpayers/{id}/production-readiness` → `ProductionReadiness { certificateValidUntil: date-time|null, authorizedTestDocuments: int, transmissionOpen: boolean, activatedAt: date-time|null, missing: string[] }` con `missing ⊆ {CERTIFICATE, TEST_AUTHORIZATION}`; `POST /api/v1/taxpayers/{id}/production-activation` con `{ "sriProductionAuthorized": true }` → `200 ProductionReadiness`.
- Produces: códigos `PRODUCTION_NOT_ENABLED` (422, al crear con ambiente `2`), `PRODUCTION_REQUIREMENTS_NOT_MET` (422, con `errors[]` de `field` ∈ `certificate`, `testAuthorization`, `sriProductionAuthorized`), `TenantAccess.requireOwner()` (403).
- Produces: `TaxpayerResponse.productionEnabledAt`.

- [ ] **Step 1:** `ProductionActivationIT` con estos casos, todos fallando: crear emisor con `defaultEnvironment: "2"` → 422 `PRODUCTION_NOT_ENABLED`; emisor nuevo → `missing = [CERTIFICATE, TEST_AUTHORIZATION]`; activar así → 422 con ambos en `errors`; con certificado y un comprobante autorizado en pruebas pero sin declaración (`false` o ausente) → 422 con `sriProductionAuthorized`; con todo → 200, `activatedAt` no nulo, el emisor pasa a ambiente `2`, hay evento `TAXPAYER_PRODUCTION_ENABLED` y la siguiente factura sale con ambiente `2`; repetir la activación → 200 sin segundo evento; `ADMIN` → 403; otra empresa → 404; certificado caducado → `CERTIFICATE` sigue faltando; un `UPDATE taxpayers SET default_environment='2'` directo sobre un emisor sin activar falla por la restricción; un comprobante de ambiente `2` cuyo emisor no está activado no se transmite aunque el ambiente `2` esté abierto (el SRI simulado no recibe nada y queda `SIGNED`).
- [ ] **Step 2:** correr `./mvnw -B verify -Dit.test=ProductionActivationIT` → falla por rutas y columnas inexistentes.
- [ ] **Step 3:** migración `V7`: columnas `production_enabled_at timestamptz`, `production_enabled_by uuid REFERENCES users(id)`; `UPDATE taxpayers SET default_environment='1' WHERE default_environment='2'`; `CHECK (default_environment = '1' OR production_enabled_at IS NOT NULL)`.
- [ ] **Step 4:** servicio, controlador, `requireOwner`, rechazo en `TaxpayerService.create`, comprobación en el envío de `DocumentPipeline` (un emisor sin activar se trata como ambiente cerrado).
- [ ] **Step 5:** contrato 0.7.0 y `npm run api:generate`.
- [ ] **Step 6:** pantalla: el alta ya no ofrece "Producción"; sección "Paso a producción" con los tres requisitos (cumplido o pendiente, en texto), la casilla de declaración y el botón "Activar producción", visible solo para el propietario; mensajes para los códigos nuevos. Pruebas de componente: muestra lo que falta; activa y refleja el ambiente nuevo; un no propietario no ve el botón.
- [ ] **Step 7:** suites de backend y frontend en verde; commit `feat: activación de producción por emisor`.

### Task 2: Observabilidad

**Files:**
- Create: `…/observability/OperationalMetrics.java`, `…/observability/MetricsSecurityConfig.java`, `backend/src/test/java/com/example/einvoice/observability/OperationalMetricsIT.java`, `…/observability/AlertRulesTest.java`, `infra/observability/prometheus-alerts.yml`
- Modify: `backend/pom.xml`, `application.yml`, `…/identity/SecurityConfig.java`, `…/sri/SriAttemptLog.java` (o donde termine cada intento), `.env.example`

**Interfaces:**
- Produces: `GET /actuator/prometheus` con `Authorization: Bearer ${EINVOICE_METRICS_TOKEN}`; sin token configurado → 404; token ausente o distinto → 401.
- Produces: métricas `einvoice_documents{state}`, `einvoice_documents_oldest_seconds{state}` (solo estados no finales), `einvoice_outbox_pending`, `einvoice_outbox_oldest_seconds`, `einvoice_deliveries{status}`, `einvoice_queue_messages{queue}`, `einvoice_certificates_expiring` (activos que caducan en 30 días o ya caducaron), `einvoice_sri_calls_seconds{operation,environment,outcome}`.
- Produces: `/actuator/health/liveness` y `/actuator/health/readiness` públicos.

- [ ] **Step 1:** `OperationalMetricsIT`: sin token configurado → 404; con token, sin cabecera → 401, con otro → 401, con el correcto → 200; tras emitir una factura hasta `AUTHORIZED` y dejar otra en `SIGNED`, el texto contiene `einvoice_documents{state="AUTHORIZED"}` y `{state="SIGNED"}` con sus cuentas, `einvoice_sri_calls_seconds_count` con `operation="RECEPTION"`, y las colas con valor; ninguna línea contiene el RUC, el nombre del emisor ni un UUID; un JWT de usuario válido no sirve como token de métricas; las sondas responden 200 sin credenciales.
- [ ] **Step 2:** verlo fallar (404 en todas).
- [ ] **Step 3:** dependencia, exposición `health,prometheus`, `management.endpoint.health.probes.enabled`, cadena de seguridad propia para `/actuator/prometheus` con comparación en tiempo constante.
- [ ] **Step 4:** `OperationalMetrics`: valores recalculados cada 30 s por consulta (no en cada lectura) y publicados como medidores; temporizador del SRI al cerrar cada intento.
- [ ] **Step 5:** reglas de alerta (cola de mensajes muertos con mensajes, comprobante atascado más de 30 min en `SENDING`/`RECEIVED`, outbox con más de 5 min, certificados por caducar, entregas fallidas, errores del SRI) y `AlertRulesTest`: cada métrica que nombra una regla existe en el registro de la aplicación.
- [ ] **Step 6:** suite en verde; commit `feat(observability): métricas, sondas y alertas`.

### Task 3: Integridad, respaldo y recuperación

**Files:**
- Create: `V8__artifact_integrity.sql`, `…/storage/ArtifactIntegrityVerifier.java`, `backend/src/test/java/com/example/einvoice/storage/ArtifactIntegrityIT.java`, `…/recovery/RecoveryIT.java`, `…/recovery/DatabaseRolesIT.java`, `infra/postgres/roles.sql`, `infra/backup/backup.sh`, `infra/backup/restore.sh`
- Modify: `…/orchestration/WorkerSchedule.java` (o planificador propio), `application.yml`, `OperationalMetrics.java`
- Test: `…/certificate/CertificateRotationIT.java` si `CertificateApiIT` no cubre ya la rotación

**Interfaces:**
- Produces: tabla `artifact_integrity (artifact_id uuid PRIMARY KEY, verified_at timestamptz, ok boolean)`; `ArtifactIntegrityVerifier.verifyNext(int limit): Result(int checked, int corrupt)`; métrica `einvoice_artifacts_corrupt`; propiedades `einvoice.integrity.batch-size` (500) e `interval` (1h).
- Produces: `infra/postgres/roles.sql` crea `einvoice_migrator` (dueño) y `einvoice_app` (solo `SELECT/INSERT/UPDATE/DELETE` y uso de secuencias, por privilegios por defecto). La aplicación los usa con `SPRING_FLYWAY_USER`/`SPRING_FLYWAY_PASSWORD` y `SPRING_DATASOURCE_USERNAME`.

- [ ] **Step 1:** `ArtifactIntegrityIT`: artefactos intactos → `corrupt = 0` y quedan marcados; uno alterado saltándose los disparadores (como dueño, dentro de la prueba) → `corrupt = 1`, la métrica vale 1 y el log nombra el artefacto sin su contenido; una segunda pasada empieza por los verificados hace más tiempo. Verlo fallar.
- [ ] **Step 2:** migración, verificador y su planificación en el worker.
- [ ] **Step 3:** `RecoveryIT`: con comprobantes autorizados y archivados, `pg_dump -Fc` dentro del contenedor, base nueva, `pg_restore --no-owner --exit-on-error`; sobre la restaurada: mismas cuentas en `fiscal_documents`, `document_artifacts`, `state_events`, `audit_events`; cada artefacto conserva su SHA-256; `UPDATE` y `DELETE` sobre `document_artifacts` siguen rechazados; `document_sequences` conserva su contador; Flyway valida sin migraciones pendientes.
- [ ] **Step 4:** `DatabaseRolesIT`: base nueva, `roles.sql`, Flyway como `einvoice_migrator`; como `einvoice_app` se puede insertar y leer, y fallan `ALTER TABLE document_artifacts DISABLE TRIGGER ALL`, `DROP TRIGGER`, `TRUNCATE document_artifacts` y `DROP TABLE`.
- [ ] **Step 5:** `backup.sh` (volcado con fecha UTC y `.sha256`) y `restore.sh` (comprueba la huella, restaura en una base vacía y falla si no lo está). Ensayo real contra la base de desarrollo local hacia una base temporal; el resultado va a la evidencia de Task 8.
- [ ] **Step 6:** rotación de certificado: nuevo certificado → los comprobantes nuevos firman con él, los ya firmados siguen verificando, y el anterior queda `REPLACED`.
- [ ] **Step 7:** suite en verde; commit `feat: verificación de integridad, respaldo y roles de base`.

### Task 4: Límites de uso y descargas de la API

**Files:**
- Create: `…/web/RateLimitFilter.java`, `…/web/RateLimitProperties.java`, `backend/src/test/java/com/example/einvoice/web/RateLimitIT.java`
- Modify: `SecurityConfig.java`, `…/storage/ArtifactController.java`, `application.yml`, `AbstractIntegrationTest.java`, `contracts/openapi.yaml`, `frontend/src/api/schema.ts`, `frontend/src/api/problem.ts`

**Interfaces:**
- Produces: `429` `application/problem+json` con `errorCode: RATE_LIMITED` y `Retry-After` (segundos hasta la siguiente ventana); propiedades `einvoice.rate-limit.requests-per-minute` (600) y `writes-per-minute` (120); métrica `einvoice_ratelimit_unavailable_total`.
- Produces: descargas con `Content-Disposition: attachment; filename="factura-EEE-PPP-SSSSSSSSS-<tipo>.<ext>"`.

- [ ] **Step 1:** `RateLimitIT` (límites bajos por propiedades): la petición que pasa del límite de escrituras → 429 con `Retry-After` entre 1 y 60 y el código; otro usuario no se ve afectado; las lecturas tienen su propio límite; en la ventana siguiente vuelve a pasar (reloj inyectado); con Redis inalcanzable la petición pasa y la métrica sube. Prueba de descarga: cabecera con el número del comprobante y `nosniff`. Verlo fallar.
- [ ] **Step 2:** filtro tras la autenticación, propiedades, cabecera de descarga.
- [ ] **Step 3:** contrato (respuesta 429 y código), esquema regenerado, mensaje en español.
- [ ] **Step 4:** suites en verde; commit `feat(api): límite de peticiones por usuario y descargas con nombre`.

### Task 5: Endurecimiento de la aplicación web

**Files:**
- Create: `frontend/src/proxy.ts` (cabeceras por petición), `frontend/src/server/csp.ts` (+ prueba), `frontend/src/server/log.ts` (+ prueba), `frontend/src/app/(app)/error.tsx`, `frontend/src/app/global-error.tsx`
- Modify: `next.config.ts`, `src/server/config.ts`, `src/app/api/backend/[...path]/route.ts`, `src/app/auth/callback/route.ts`, `src/server/sessionStore.ts`, `src/api/problem.ts`, `src/lib/drafts.ts`, y sus pruebas

**Interfaces:**
- Produces: `buildCsp(nonce: string, development: boolean): string` — `default-src 'self'`; `script-src 'self' 'nonce-…' 'strict-dynamic'` (más `'unsafe-eval'` solo en desarrollo); `style-src 'self' 'unsafe-inline'`; `img-src 'self' data:`; `connect-src 'self'`; `object-src 'none'`; `frame-ancestors 'none'`; `base-uri 'self'`; `form-action 'self'`.
- Produces: `logEvent(level: "info" | "warn" | "error", event: string, fields?: Record<string, string | number | boolean | null>)` → una línea JSON; eventos `login.failed`, `session.refresh.rejected`, `session.refresh.unavailable`, `proxy.upstream.failed`, `proxy.upstream.timeout`.
- Produces: el proxy corta la llamada a la API a los 30 s → `504`.

- [ ] **Step 1:** pruebas que fallan: `buildCsp` (política exacta, `'unsafe-eval'` solo en desarrollo, el nonce aparece una vez); `readConfig` rechaza `APP_BASE_URL` en `http` fuera de `localhost`; la ruta del proxy responde 504 cuando la API no contesta a tiempo (plazo inyectable) y registra `proxy.upstream.timeout` con el `correlationId` que devuelve; un fallo de la vuelta del proveedor registra `login.failed`; ninguna línea de log contiene el token de la sesión; `describeError` dice "El servidor no está disponible…" para 502, 503 y 504; `isDraft` descarta borradores con líneas, pagos o fecha mal formados.
- [ ] **Step 2:** implementación; `error.tsx` y `global-error.tsx` en español con "Reintentar".
- [ ] **Step 3:** comprobación en el navegador integrado, con el servidor de desarrollo y con `next build && next start`: `/ingresar` y una pantalla interna cargan sin infracciones de la política en la consola; inicio y cierre de sesión completos con el script `check-logout.sh`.
- [ ] **Step 4:** suite en verde; commit `feat(frontend): política de contenido, registro de eventos y errores propios`.

### Task 6: Límites de uso real del editor

**Files:**
- Modify: `frontend/src/app/(app)/facturas/nueva/page.tsx`, `src/components/Notice.tsx`, `src/lib/decimal.ts` (si hace falta), y sus pruebas

- [ ] **Step 1:** pruebas que fallan: el producto de una línea se busca por nombre o código contra la API (`GET /products?q=`), con espera, y se puede elegir uno que no está entre los primeros 100; un borrador que referencia un producto fuera de la primera página lo muestra por su nombre; cantidad `"2 "` se envía como `"2"`; un 400 sin campo marcable muestra el detalle del primer error; tras el primer guardado la dirección lleva `?borrador=<id>`; el enlace "Iniciar sesión" de `ErrorNotice` conserva la consulta; con el botón "Emitir factura" deshabilitado se dice qué falta.
- [ ] **Step 2:** implementación.
- [ ] **Step 3:** suite en verde; commit `feat(frontend): búsqueda de productos y avisos del editor`.

### Task 7: Imágenes, staging de referencia y CI

**Files:**
- Create: `backend/Dockerfile`, `backend/.dockerignore`, `frontend/Dockerfile`, `frontend/.dockerignore`, `infra/docker-compose.stack.yml`
- Modify: `frontend/next.config.ts` (`output: "standalone"`), `backend/pom.xml` (JaCoCo con umbral, CycloneDX), `.github/workflows/ci.yml`, `.env.example`

**Interfaces:**
- Produces: imágenes `einvoice-api` (Java 21, usuario no root, sirve API y worker según `EINVOICE_WORKER_ENABLED`) y `einvoice-web` (Node 24, no root).
- Produces: `docker compose -f infra/docker-compose.yml -f infra/docker-compose.stack.yml up -d --build` levanta `api`, `worker` y `web`; solo `web` publica puerto, en `127.0.0.1`.

- [ ] **Step 1:** JaCoCo: medir la cobertura de líneas de `sequence`, `billing`, `xml`, `signature`, `sri` y `certificate`; fijar el umbral de cada paquete en el múltiplo de 5 inmediatamente inferior a lo medido (mínimo 70); comprobar que bajarlo artificialmente hace fallar `verify`.
- [ ] **Step 2:** Dockerfiles en varias etapas; construir ambas imágenes; comprobar `docker run --rm <imagen> id -u` distinto de 0.
- [ ] **Step 3:** compose de staging; levantarlo; comprobar: `api` y `worker` sanos, migraciones aplicadas, `web` responde `/ingresar` con la política de contenido y `/api/session` con `authenticated: false`, y desde el contenedor `web` se alcanza `http://api:8080/actuator/health/readiness`. Bajarlo.
- [ ] **Step 4:** CI: SBOM de backend (CycloneDX) y de frontend (`npm sbom`) como artefactos, `npm audit --omit=dev --audit-level=high`, revisión de dependencias en pull requests, construcción de ambas imágenes sin publicarlas.
- [ ] **Step 5:** suites en verde; commit `build: imágenes, staging de referencia, cobertura y SBOM`.

### Task 8: Matriz de certificación, evidencias y documentación

**Files:**
- Create: `backend/src/test/java/com/example/einvoice/certification/CertificationMatrix.java`, `…/CertificationMatrixIT.java`, `…/SriCertificationIT.java`, `…/EvidenceReport.java`, `docs/15-produccion.md`
- Modify: `AbstractIntegrationTest.java`, `backend/pom.xml` (perfil `certification`), `README.md`, `docs/10-security.md`, `docs/12-testing-certification.md`, `docs/13-deployment.md`, `docs/DEVELOPMENT.md`, `docs/superpowers/plans/2026-10-10-plan-maestro.md`

**Interfaces:**
- Produces: `CertificationMatrix.cases()`: factura a RUC, a cédula, a pasaporte, a consumidor final; con IVA 0 %, 15 % y mixto; con descuento; con varios campos adicionales; con una forma de pago distinta de efectivo.
- Produces: `EvidenceReport` escribe `target/certification/evidence.json` y `evidence.md`: por caso, nombre, clave de acceso, estado final, SHA-256 del XML firmado, número y fecha de autorización, códigos de mensajes del SRI; y de cabecera, fecha, ambiente, versión de esquema, versión de ficha y commit. Sin nombres ni identificaciones de adquirentes.
- Produces: `./mvnw -B verify -Pcertification` con `SRI_CERT_P12_PATH`, `SRI_CERT_P12_PASSWORD`, `SRI_CERT_RUC`, `SRI_CERT_LEGAL_NAME`, `SRI_CERT_ADDRESS` y, opcionales, `SRI_CERT_ESTABLISHMENT` (001), `SRI_CERT_POINT` (001), `SRI_CERT_START_SEQUENCE`, `SRI_CERT_BUYER_CEDULA`.

- [ ] **Step 1:** `CertificationMatrixIT` (contra el SRI simulado, corre siempre): cada caso termina `AUTHORIZED`, su XML pasa el XSD y la firma verifica; el informe existe, tiene una entrada por caso con todos sus campos y no contiene la identificación ni el nombre de ningún adquirente. Verlo fallar.
- [ ] **Step 2:** matriz, informe y `SriCertificationIT`: usa los servicios oficiales del ambiente de pruebas, se salta si falta alguna variable, y en modo certificación ninguna otra prueba de integración corre (se saltan con una suposición en `AbstractIntegrationTest`). Comprobar que sin el perfil no se ejecuta y que con el perfil y sin variables se salta diciendo cuáles faltan.
- [ ] **Step 3:** `docs/15-produccion.md`: estado de los gates G1 a G6 con su evidencia o lo que falta; la matriz del doc 12 fila por fila con la prueba que la cubre o "requiere SRI real"; guías paso a paso para certificar con un certificado real, activar un emisor, respaldar y restaurar, y recoger métricas; el checklist de preproducción del doc 10 con responsable y estado.
- [ ] **Step 4:** resto de documentos y plan maestro al día.
- [ ] **Step 5:** suites completas en verde; commit `docs: certificación y producción`.

---

## Lo que este plan no puede cerrar

G3 (SRI real por versión y tipo), G5 (emisor habilitado en producción) y G6 (checklist legal del proveedor) dependen de un certificado real, un RUC habilitado y decisiones del responsable del producto. Tampoco: prueba de penetración, gestor de claves, almacenamiento de objetos, proveedor de identidad de producción, despliegue en una nube concreta ni pruebas automatizadas con navegador. El plan deja cada uno con su guía y su estado en `docs/15-produccion.md`.
