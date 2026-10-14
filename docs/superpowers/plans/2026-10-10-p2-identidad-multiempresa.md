# Plan 2 — Identidad y multiempresa: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que un usuario autenticado por OIDC pueda crear su empresa (tenant), dar de alta emisores, establecimientos y puntos de emisión, con roles y con aislamiento estricto entre empresas.

**Architecture:** el backend es un *resource server* OAuth2: valida JWT de cualquier emisor OIDC configurado y no gestiona sesiones ni contraseñas. El usuario se identifica por `(iss, sub)` y se crea en su primera petición. El tenant activo se resuelve en cada petición a partir de las membresías del usuario (cabecera `X-Tenant-Id` verificada contra ellas), nunca del cuerpo. Todas las consultas filtran por `tenant_id` y los recursos ajenos responden 404.

**Tech Stack:** Spring Security OAuth2 Resource Server, Spring Data JPA, Flyway, PostgreSQL 16, MockMvc, Testcontainers.

**Spec:** [docs/04-database-design.md](../../04-database-design.md), [docs/10-security.md](../../10-security.md), [docs/11-api-specification.md](../../11-api-specification.md), [docs/09-error-handling.md](../../09-error-handling.md) (sección "Respuestas API"), [docs/01-product-requirements.md](../../01-product-requirements.md) (PR-01, PR-02). Contexto: [plan maestro](2026-10-10-plan-maestro.md).

## Global Constraints

- Las del Plan 1 siguen vigentes (Java 21, paquete raíz `com.example.einvoice`, secretos fuera del repo, `./mvnw -B verify` verde y un commit por tarea).
- Base de la API: `/api/v1`. JSON en camelCase. Identificadores UUID.
- `tenantId` nunca se lee del cuerpo ni de un parámetro: sale del contexto autenticado y verificado.
- Un recurso de otro tenant responde `404` con el mismo cuerpo que un recurso inexistente.
- Errores en `application/problem+json` con `type`, `title`, `status`, `detail`, `errorCode` y `correlationId`. El `detail` no incluye datos internos ni de otros tenants.
- Todo alta deja una fila en `audit_events` en la misma transacción.
- Las migraciones existentes no se editan; esta es `V2__`.
- Códigos de establecimiento y punto de emisión son texto de 3 dígitos; el RUC es texto de 13 dígitos. Nunca se convierten a número.
- Roles: `OWNER`, `ADMIN`, `BILLER`, `AUDITOR`. Escriben emisores, establecimientos y puntos solo `OWNER` y `ADMIN`; leen todos.

## Decisiones de este plan

- **Proveedor OIDC:** indiferente. Se configura con `OIDC_ISSUER` y `OIDC_AUDIENCE`; la aplicación no arranca sin ellos. La audiencia se añadió tras la revisión final: sin ella, un token emitido por el mismo proveedor para otra aplicación servía aquí.
- **`POST /auth/sessions` (doc 11):** no se implementa; la sesión es del proveedor de identidad.
- **Alta de tenant:** `POST /tenants`, que no estaba en el doc 11; el creador queda como `OWNER`.
- **Selección de tenant:** cabecera `X-Tenant-Id`. Si el usuario tiene una sola membresía activa, la cabecera es opcional.
- **Invitación de usuarios a un tenant:** fuera de este plan (llega con el onboarding del Plan 7). Las pruebas crean membresías directamente en la base.
- **Validación del RUC:** solo formato (13 dígitos). El dígito verificador depende del tipo de contribuyente y se define con la ficha (tarea N2).
- **Tipos de columna:** `varchar` con `CHECK` en lugar de `char(n)`, para que Hibernate valide el esquema sin anotaciones especiales.

## Review Focus

- **Id de otro tenant en una ruta anidada o en el cuerpo.** Crear un establecimiento bajo el emisor de otro tenant, o un punto de emisión con el `establishmentId` de otro tenant. Esperado: 404 y ninguna fila creada. Tests en Tasks 5 y 6.
- **Mismo RUC registrado a la vez por dos tenants.** Esperado: exactamente un 201 y un 409. Test en Task 5.
- **Membresía suspendida con token aún válido.** Esperado: 403. Test en Task 4.
- **Cabecera `X-Tenant-Id` de un tenant inexistente o ajeno.** Esperado: 403 con el mismo cuerpo en ambos casos, sin revelar si existe. Test en Task 4.
- **Token caducado, de otro emisor o firmado con otra clave.** Esperado: 401. Test en Task 2.

## Estructura de archivos

```text
backend/src/main/resources/db/migration/V2__identity_tenancy.sql
backend/src/main/java/com/example/einvoice/
  web/ApiException.java            # estado + errorCode + detail
  web/ApiExceptionHandler.java     # problem+json
  web/CorrelationIdFilter.java     # X-Correlation-ID en MDC, atributo y respuesta
  identity/SecurityConfig.java     # resource server, rutas públicas, 401/403 en problem+json
  identity/UserIdentity.java       # record(userId, issuer, subject, email)
  identity/UserDirectory.java      # upsert de users por (iss, sub)
  identity/UserIdentityResolver.java
  tenancy/Role.java, MembershipStatus.java, TenantStatus.java
  tenancy/Tenant.java, Membership.java (+ repositorios)
  tenancy/TenantAccess.java        # record(userId, tenantId, role) + requireWrite()
  tenancy/TenantAccessResolver.java
  tenancy/TenantService.java, TenantController.java
  taxpayer/Taxpayer.java, Establishment.java, EmissionPoint.java (+ repositorios)
  taxpayer/TaxpayerService.java, TaxpayerController.java
  taxpayer/EstablishmentController.java, EmissionPointController.java
  compliance/AuditLog.java         # INSERT en audit_events
contracts/openapi.yaml
backend/src/test/java/com/example/einvoice/
  support/ContainersConfig.java, TestSecurityConfig.java, TestTokens.java, AbstractIntegrationTest.java
  IdentitySchemaIT.java, SecurityIT.java
  tenancy/TenantApiIT.java, TenantContextIT.java
  taxpayer/TaxpayerApiIT.java, EstablishmentApiIT.java, EmissionPointApiIT.java
  TenantIsolationIT.java, OpenApiContractIT.java
```

---

### Task 1: Esquema de identidad y multiempresa, y base de pruebas de integración

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__identity_tenancy.sql`, `backend/src/test/java/com/example/einvoice/support/ContainersConfig.java`, `support/AbstractIntegrationTest.java`
- Modify: `backend/src/test/java/com/example/einvoice/InfrastructureIT.java` (pasa a extender la base y usa MockMvc)
- Test: `backend/src/test/java/com/example/einvoice/IdentitySchemaIT.java`

**Interfaces:**
- Produces:
  - `ContainersConfig`: `@TestConfiguration` con tres beans `@ServiceConnection` (`postgres:16`, `rabbitmq:4-management`, `redis:7-alpine`).
  - `AbstractIntegrationTest`: `@SpringBootTest(properties = {"OIDC_ISSUER=https://idp.test", "management.endpoint.health.show-components=always"})`, `@AutoConfigureMockMvc`, `@Import(ContainersConfig.class)`; expone `protected MockMvc mvc` y `protected JdbcTemplate jdbc`. Todas las clases `*IT` la extienden para compartir un solo contexto y un solo juego de contenedores.
  - Tablas: `tenants`, `users`, `memberships`, `taxpayers`, `establishments`, `emission_points`, `audit_events`.

- [ ] **Step 1: Escribir `IdentitySchemaIT`**

Inserta con `jdbc` y espera `DataIntegrityViolationException` donde corresponde. Casos:

| Test | Acción | Esperado |
|---|---|---|
| `rucIsUniqueAcrossTenants` | dos tenants, mismo RUC en `taxpayers` | la segunda inserción falla |
| `rucMustBeThirteenDigits` | RUC `179001234500` y `17900123450AB` | fallan |
| `establishmentCannotPointToTaxpayerOfAnotherTenant` | `establishments(tenant_id = B, taxpayer_id = emisor de A)` | falla |
| `emissionPointCannotPointToEstablishmentOfAnotherTenant` | análogo | falla |
| `establishmentCodeIsUniquePerTaxpayerAndKeepsLeadingZeros` | código `001` dos veces en el mismo emisor; leerlo de vuelta | segunda falla; se lee `"001"` |
| `membershipIsUniquePerUserAndTenant` | misma pareja dos veces | falla |
| `roleAndEnvironmentAreConstrained` | rol `ROOT`; ambiente `3` | fallan |

- [ ] **Step 2: Crear `ContainersConfig` y `AbstractIntegrationTest`, migrar `InfrastructureIT` y ejecutar**

Run (desde `backend/`): `./mvnw -B verify -Dit.test=IdentitySchemaIT`
Expected: FAIL, las tablas no existen (`relation "tenants" does not exist`).

- [ ] **Step 3: Escribir la migración**

```sql
CREATE TABLE tenants (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
 status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 issuer text NOT NULL,
 subject text NOT NULL,
 email text,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (issuer, subject)
);
CREATE TABLE memberships (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 role varchar(20) NOT NULL CHECK (role IN ('OWNER','ADMIN','BILLER','AUDITOR')),
 status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (user_id, tenant_id)
);
CREATE TABLE taxpayers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 ruc varchar(13) NOT NULL UNIQUE CHECK (ruc ~ '^[0-9]{13}$'),
 legal_name text NOT NULL,
 trade_name text,
 head_office_address text NOT NULL,
 provider_ruc varchar(13) CHECK (provider_ruc ~ '^[0-9]{13}$'),
 default_environment varchar(1) NOT NULL CHECK (default_environment IN ('1','2')),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (tenant_id, id)
);
CREATE TABLE establishments (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 taxpayer_id uuid NOT NULL,
 code varchar(3) NOT NULL CHECK (code ~ '^[0-9]{3}$'),
 address text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (tenant_id, taxpayer_id) REFERENCES taxpayers(tenant_id, id),
 UNIQUE (taxpayer_id, code),
 UNIQUE (tenant_id, id)
);
CREATE TABLE emission_points (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 establishment_id uuid NOT NULL,
 code varchar(3) NOT NULL CHECK (code ~ '^[0-9]{3}$'),
 enabled boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (tenant_id, establishment_id) REFERENCES establishments(tenant_id, id),
 UNIQUE (establishment_id, code)
);
CREATE TABLE audit_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid REFERENCES tenants(id),
 actor_id uuid NOT NULL REFERENCES users(id),
 action varchar(60) NOT NULL,
 entity_type varchar(60) NOT NULL,
 entity_id uuid NOT NULL,
 metadata jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_events_tenant ON audit_events (tenant_id, created_at DESC);
CREATE INDEX idx_memberships_user ON memberships (user_id);
```

- [ ] **Step 4: Ejecutar y ver que pasa**

Run: `./mvnw -B verify`
Expected: `BUILD SUCCESS`; `IdentitySchemaIT` 7 tests; `InfrastructureIT` sigue en verde.

- [ ] **Step 5: Commit** — `feat(db): esquema de identidad y multiempresa`

---

### Task 2: Seguridad base

**Files:**
- Create: `web/ApiException.java`, `web/ApiExceptionHandler.java`, `web/CorrelationIdFilter.java`, `identity/SecurityConfig.java`; test: `support/TestSecurityConfig.java`, `support/TestTokens.java`
- Modify: `backend/pom.xml` (starter `spring-boot-starter-security-oauth2-resource-server` y su `-test`), `application.yml`, `support/AbstractIntegrationTest.java` (importa `TestSecurityConfig`)
- Test: `backend/src/test/java/com/example/einvoice/SecurityIT.java`

**Interfaces:**
- Produces:
  - `class ApiException extends RuntimeException` con `ApiException(HttpStatus status, String errorCode, String detail)` y fábrica `static ApiException notFound()` (`404`, `NOT_FOUND`, detalle `Recurso no encontrado`).
  - `CorrelationIdFilter`: toma `X-Correlation-ID` si cumple `[A-Za-z0-9._-]{1,64}`, si no genera un UUID; lo deja en el atributo de petición `correlationId`, en MDC y en la cabecera de respuesta.
  - `TestTokens`: `static String bearer(String subject)`, `bearer(String subject, String email)`, `expired(String subject)`, `wrongIssuer(String subject)`, `wrongKey(String subject)`; emisor `https://idp.test`, RSA generada en memoria.
  - `TestSecurityConfig`: bean `JwtDecoder` con la clave pública de `TestTokens` y los validadores por defecto para el emisor `https://idp.test`.
- Configuración: `spring.security.oauth2.resourceserver.jwt.issuer-uri: ${OIDC_ISSUER}`. Sin sesión, sin CSRF. Públicos: `/actuator/health` y `/actuator/health/**`. Todo lo demás autenticado.

- [ ] **Step 1: Escribir `SecurityIT`**

| Test | Petición | Esperado |
|---|---|---|
| `healthIsPublic` | `GET /actuator/health` sin token | 200 |
| `apiRequiresToken` | `GET /api/v1/tenants` sin token | 401, `application/problem+json`, `$.errorCode = UNAUTHENTICATED`, `$.correlationId` presente |
| `rejectsExpiredToken` | con `TestTokens.expired` | 401 |
| `rejectsTokenFromAnotherIssuer` | con `wrongIssuer` | 401 |
| `rejectsTokenSignedWithAnotherKey` | con `wrongKey` | 401 |
| `unknownRouteIsProblemNotFound` | `GET /api/v1/no-existe` con token válido | 404, `$.errorCode = NOT_FOUND` |
| `echoesCorrelationId` | cabecera `X-Correlation-ID: abc-123` | la respuesta trae la misma cabecera y `$.correlationId = abc-123` en el cuerpo de error |
| `replacesUnsafeCorrelationId` | cabecera con espacios o más de 64 caracteres | la respuesta trae un valor distinto que cumple el patrón |

- [ ] **Step 2: Ejecutar y ver que falla** — `./mvnw -B verify -Dit.test=SecurityIT`. Expected: no compila (`TestTokens` no existe).
- [ ] **Step 3: Implementar.** `ApiExceptionHandler` cubre `ApiException`, `MethodArgumentNotValidException` (`400 VALIDATION_FAILED`, propiedad `errors` con `field` y `message`), `HttpMessageNotReadableException` (`400 MALFORMED_REQUEST`), `MethodArgumentTypeMismatchException` (`400 MALFORMED_REQUEST`), ruta inexistente (`404 NOT_FOUND`) y cualquier otra (`500 INTERNAL_ERROR`, detalle genérico, traza solo en log). El punto de entrada de autenticación y el manejador de acceso denegado escriben el mismo formato (`401 UNAUTHENTICATED`, `403 FORBIDDEN`).
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`. Expected: `SecurityIT` 8 tests; el resto en verde.
- [ ] **Step 5: Commit** — `feat(identity): resource server JWT y errores problem+json`

---

### Task 3: Usuarios y tenants

**Files:**
- Create: `identity/UserIdentity.java`, `identity/UserDirectory.java`, `identity/UserIdentityResolver.java`, `tenancy/Role.java`, `MembershipStatus.java`, `TenantStatus.java`, `Tenant.java`, `TenantRepository.java`, `Membership.java`, `MembershipRepository.java`, `TenantService.java`, `TenantController.java`, `compliance/AuditLog.java`, una `WebMvcConfigurer` que registre los resolvers
- Test: `backend/src/test/java/com/example/einvoice/tenancy/TenantApiIT.java`

**Interfaces:**
- Consumes: `ApiException`, `TestTokens`, `AbstractIntegrationTest`.
- Produces:
  - `record UserIdentity(UUID userId, String issuer, String subject, String email)`; un parámetro de controlador de este tipo se resuelve desde el JWT, creando el usuario si no existe.
  - `UserDirectory.resolve(String issuer, String subject, String email): UUID` — `INSERT ... ON CONFLICT (issuer, subject) DO UPDATE SET email = COALESCE(EXCLUDED.email, users.email) RETURNING id`.
  - `AuditLog.record(UUID tenantId, UUID actorId, String action, String entityType, UUID entityId, Map<String, Object> metadata)`; participa en la transacción en curso.
  - `enum Role { OWNER, ADMIN, BILLER, AUDITOR }` con `boolean canWrite()` (verdadero para `OWNER` y `ADMIN`).
  - `MembershipRepository.findByUserIdAndStatus(UUID, MembershipStatus)` y `findByUserIdAndTenantId(UUID, UUID)`.
- API:
  - `POST /api/v1/tenants` cuerpo `{"name": "..."}` (1 a 200 caracteres tras recortar) → `201` `{"id","name","status":"ACTIVE","role":"OWNER"}`. Acción de auditoría `TENANT_CREATED`.
  - `GET /api/v1/tenants` → `200` lista de los tenants con membresía `ACTIVE` del usuario, mismo formato.

- [ ] **Step 1: Escribir `TenantApiIT`**

| Test | Esperado |
|---|---|
| `createsTenantWithCallerAsOwner` | 201; `role = OWNER`; existe la fila en `memberships`; una fila `TENANT_CREATED` en `audit_events` con ese `entity_id` |
| `listsOnlyCallerTenants` | A crea dos tenants y B uno; `GET` de A devuelve exactamente los dos suyos |
| `sameSubjectIsOneUser` | dos peticiones del mismo `sub` dejan una sola fila en `users` |
| `rejectsBlankOrTooLongName` | `""`, `"   "` y 201 caracteres → 400 `VALIDATION_FAILED` |
| `rejectsMalformedJson` | cuerpo `{` → 400 `MALFORMED_REQUEST` |
| `ignoresTenantIdInBody` | cuerpo con `"id"` y `"tenantId"` ajenos → 201 con un id distinto al enviado |

- [ ] **Step 2: Ejecutar y ver que falla** — `-Dit.test=TenantApiIT`. Expected: 404 en `POST /api/v1/tenants`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(tenancy): alta y listado de tenants con usuario propietario`

---

### Task 4: Contexto de tenant y roles

**Files:**
- Create: `tenancy/TenantAccess.java`, `tenancy/TenantAccessResolver.java`
- Modify: `tenancy/TenantController.java` (añade `GET /api/v1/tenants/current`), la `WebMvcConfigurer` de Task 3
- Test: `backend/src/test/java/com/example/einvoice/tenancy/TenantContextIT.java`

**Interfaces:**
- Produces: `record TenantAccess(UUID userId, UUID tenantId, Role role)` con `void requireWrite()` (lanza `403 ROLE_FORBIDDEN` si el rol no escribe). Un parámetro de controlador de este tipo se resuelve así:
  1. Sin cabecera `X-Tenant-Id`: si el usuario tiene exactamente una membresía `ACTIVE` en un tenant `ACTIVE`, es esa; si tiene varias, `400 TENANT_REQUIRED`; si no tiene ninguna, `403 TENANT_FORBIDDEN`.
  2. Con cabecera que no es un UUID: `400 TENANT_HEADER_INVALID`.
  3. Con cabecera UUID: debe existir una membresía `ACTIVE` del usuario en ese tenant y el tenant estar `ACTIVE`; en cualquier otro caso `403 TENANT_FORBIDDEN` con detalle fijo `Sin acceso al tenant indicado`.
- API: `GET /api/v1/tenants/current` → `200` `{"id","name","status","role"}`.

- [ ] **Step 1: Escribir `TenantContextIT`**

| Test | Esperado |
|---|---|
| `singleMembershipNeedsNoHeader` | 200 con el tenant del usuario |
| `severalMembershipsRequireHeader` | sin cabecera 400 `TENANT_REQUIRED`; con cabecera 200 y el tenant pedido |
| `userWithoutTenantIsForbidden` | 403 `TENANT_FORBIDDEN` |
| `malformedHeaderIsBadRequest` | `X-Tenant-Id: abc` → 400 `TENANT_HEADER_INVALID` |
| `foreignAndUnknownTenantLookTheSame` | tenant de otro usuario y UUID aleatorio → ambos 403 con `detail` y `errorCode` idénticos |
| `suspendedMembershipIsForbidden` | `UPDATE memberships SET status='SUSPENDED'` → 403 |
| `suspendedTenantIsForbidden` | `UPDATE tenants SET status='SUSPENDED'` → 403 |
| `roleComesFromMembership` | membresía insertada como `AUDITOR` → `role = AUDITOR` |

- [ ] **Step 2: Ejecutar y ver que falla** — `-Dit.test=TenantContextIT`. Expected: 404 en `/tenants/current`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(tenancy): contexto de tenant verificado y roles`

---

### Task 5: Emisores

**Files:**
- Create: `taxpayer/Taxpayer.java`, `TaxpayerRepository.java`, `TaxpayerService.java`, `TaxpayerController.java`
- Test: `backend/src/test/java/com/example/einvoice/taxpayer/TaxpayerApiIT.java`, `backend/src/test/java/com/example/einvoice/TenantIsolationIT.java`

**Interfaces:**
- Consumes: `TenantAccess` (Task 4), `AuditLog` (Task 3).
- Produces: `TaxpayerRepository.findByIdAndTenantId(UUID, UUID): Optional<Taxpayer>`, `findAllByTenantIdOrderByLegalName(UUID)`; `TaxpayerService.get(TenantAccess, UUID): Taxpayer` (lanza `ApiException.notFound()`).
- API:
  - `POST /api/v1/taxpayers` cuerpo `{"ruc","legalName","tradeName"?,"headOfficeAddress","defaultEnvironment"}` → `201` con esos campos más `id`. Reglas: `ruc` `[0-9]{13}`; `legalName` y `headOfficeAddress` 1 a 300 caracteres; `tradeName` hasta 300; `defaultEnvironment` `1` o `2`. Requiere rol que escribe. RUC ya registrado en cualquier tenant → `409 RUC_ALREADY_REGISTERED` con detalle `El RUC ya está registrado en la plataforma`. Auditoría `TAXPAYER_CREATED`.
  - `GET /api/v1/taxpayers` → lista del tenant activo. `GET /api/v1/taxpayers/{id}` → `200` o `404`.

- [ ] **Step 1: Escribir `TaxpayerApiIT` y `TenantIsolationIT`**

`TaxpayerApiIT`:

| Test | Esperado |
|---|---|
| `ownerCreatesTaxpayer` | 201; `GET` por id devuelve lo mismo; fila `TAXPAYER_CREATED` |
| `billerAndAuditorCanReadButNotCreate` | `POST` → 403 `ROLE_FORBIDDEN`; `GET` → 200 |
| `rejectsInvalidPayload` | RUC de 12 dígitos, con letras, con espacios; ambiente `3`; `legalName` vacío → 400 `VALIDATION_FAILED` con el campo en `errors` |
| `sameRucInSameTenantIsConflict` | 409 `RUC_ALREADY_REGISTERED` |
| `sameRucInAnotherTenantIsConflictWithoutLeak` | 409; el cuerpo no contiene el id ni el nombre del otro tenant |
| `concurrentRegistrationOfSameRucYieldsOneWinner` | dos tenants, dos hilos con un `CountDownLatch` → exactamente un 201 y un 409; una sola fila con ese RUC |
| `rucKeepsLeadingZero` | RUC `0990012345001` se devuelve idéntico |

`TenantIsolationIT` (parte de emisores):

| Test | Esperado |
|---|---|
| `taxpayerOfAnotherTenantIsNotFound` | `GET /taxpayers/{id de A}` como B → 404, cuerpo igual al de un UUID aleatorio |
| `listNeverIncludesAnotherTenant` | la lista de B no contiene el RUC de A |

- [ ] **Step 2: Ejecutar y ver que falla** — `-Dit.test='TaxpayerApiIT,TenantIsolationIT'`. Expected: 404 en `/taxpayers`.
- [ ] **Step 3: Implementar.** El 409 se decide con la restricción `UNIQUE` de la base (capturar la violación), no solo con una consulta previa: es lo que resuelve la carrera.
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(taxpayer): alta y consulta de emisores con RUC único`

---

### Task 6: Establecimientos y puntos de emisión

**Files:**
- Create: `taxpayer/Establishment.java`, `EstablishmentRepository.java`, `EmissionPoint.java`, `EmissionPointRepository.java`, `EstablishmentController.java`, `EmissionPointController.java`
- Modify: `taxpayer/TaxpayerService.java`, `TenantIsolationIT.java`
- Test: `taxpayer/EstablishmentApiIT.java`, `taxpayer/EmissionPointApiIT.java`

**Interfaces:**
- Consumes: `TaxpayerService.get(TenantAccess, UUID)`.
- API:
  - `POST /api/v1/taxpayers/{taxpayerId}/establishments` cuerpo `{"code","address"}` → `201` `{"id","taxpayerId","code","address"}`. `code` `[0-9]{3}`, `address` 1 a 300. Duplicado → `409 ESTABLISHMENT_CODE_TAKEN`. Auditoría `ESTABLISHMENT_CREATED`.
  - `GET /api/v1/taxpayers/{taxpayerId}/establishments` → lista ordenada por código.
  - `POST /api/v1/emission-points` cuerpo `{"establishmentId","code"}` → `201` `{"id","establishmentId","code","enabled":true}`. Duplicado → `409 EMISSION_POINT_CODE_TAKEN`. Auditoría `EMISSION_POINT_CREATED`.
  - `GET /api/v1/emission-points?establishmentId=` → lista ordenada por código; el parámetro es obligatorio (`400 MALFORMED_REQUEST` si falta).
  - Emisor o establecimiento inexistente o de otro tenant → `404` en todas.

- [ ] **Step 1: Escribir los tests**

`EstablishmentApiIT`: `createsAndListsEstablishments` (códigos `002` y `001` se listan como `001`, `002`); `duplicateCodeIsConflict`; `sameCodeUnderAnotherTaxpayerIsAllowed`; `rejectsInvalidCode` (`1`, `0001`, `A01`); `readOnlyRoleCannotCreate`; `unknownTaxpayerIsNotFound`.

`EmissionPointApiIT`: `createsAndListsEmissionPoints`; `duplicateCodeIsConflict`; `sameCodeUnderAnotherEstablishmentIsAllowed`; `rejectsInvalidCode`; `readOnlyRoleCannotCreate`; `missingEstablishmentIdIsBadRequest`.

`TenantIsolationIT` (añadir):

| Test | Esperado |
|---|---|
| `cannotCreateEstablishmentUnderForeignTaxpayer` | 404; `SELECT count(*) FROM establishments WHERE taxpayer_id = ?` no cambia |
| `cannotListEstablishmentsOfForeignTaxpayer` | 404 |
| `cannotCreateEmissionPointUnderForeignEstablishment` | 404; ninguna fila nueva |
| `cannotListEmissionPointsOfForeignEstablishment` | 404 |

- [ ] **Step 2: Ejecutar y ver que falla.**
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(taxpayer): establecimientos y puntos de emisión`

---

### Task 7: Contrato OpenAPI y documentación

**Files:**
- Create: `contracts/openapi.yaml`
- Modify: `docs/11-api-specification.md`, `docs/04-database-design.md`, `docs/13-deployment.md`, `README.md`, `docs/superpowers/plans/2026-10-10-plan-maestro.md`, `.github/workflows/ci.yml` (nada si no hace falta)
- Test: `backend/src/test/java/com/example/einvoice/OpenApiContractIT.java`

**Interfaces:**
- Consumes: todos los controladores.
- Contrato: OpenAPI 3.1 con las rutas de este plan, esquemas de petición y respuesta, el esquema `Problem`, seguridad `bearerAuth` y el parámetro de cabecera `X-Tenant-Id`.

- [ ] **Step 1: Escribir `OpenApiContractIT`**

Lee `../contracts/openapi.yaml` y obtiene de `RequestMappingHandlerMapping` todos los pares método + ruta bajo `/api/v1`. Dos aserciones: toda ruta implementada está en el contrato, y toda ruta del contrato está implementada.

- [ ] **Step 2: Ejecutar y ver que falla** — el archivo no existe.
- [ ] **Step 3: Escribir el contrato.**
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Actualizar la documentación**

- Doc 11: tabla de rutas con lo implementado (`POST/GET /tenants`, `GET /tenants/current`, emisores, establecimientos, puntos), la cabecera `X-Tenant-Id`, los roles y los códigos de error; marcar `POST /auth/sessions` como no aplicable.
- Doc 04: nota de que las migraciones reales están en `backend/src/main/resources/db/migration` y usan `varchar` con `CHECK`; añadir `id` a `memberships` en el modelo lógico.
- Doc 13: añadir `OIDC_ISSUER` como variable obligatoria para arrancar.
- README y plan maestro: estado del Plan 2.

- [ ] **Step 6: Commit** — `docs: contrato OpenAPI y documentación de identidad y multiempresa`

---

## Fuera de este plan

- Invitación y gestión de miembros de un tenant; edición y baja de emisores, establecimientos y puntos.
- Validación del dígito verificador del RUC.
- Clientes API con *client credentials* y scopes finos (PR-10); MFA; RLS en PostgreSQL.
- Certificados, clientes, productos, facturas y secuenciales (Planes 3 y 4).
