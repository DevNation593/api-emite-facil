# API keys para sistemas externos — diseño

Fecha: 2026-10-15 · Rama: `p11-api-keys` (en `api-emite-facil` y `client-factura-facil`), salida de
`p10-autenticacion-propia`.

## Objetivo

Que otros sistemas (ERP, punto de venta, tienda en línea) llamen a la API de facturación sin el inicio
de sesión de una persona. Una persona con rol OWNER o ADMIN genera una API key para su empresa, la
copia una sola vez y la configura en el otro sistema. Puede revocarla cuando quiera.

## Decisiones tomadas

| Tema | Decisión |
|---|---|
| Alcance de una key | Lo de un BILLER: clientes, productos, emitir y consultar comprobantes, descargas, reenvío de correo |
| Autoría | La key actúa en nombre de quien la creó; la auditoría guarda además qué key fue |
| Transporte | Cabecera propia `X-Api-Key` (no `Authorization: Bearer`) |
| Gestión | Endpoints en la API y una sección en la página Integraciones de la web |
| Caducidad | Ninguna; solo revocación, inmediata e irreversible |

Fuera de alcance: elegir el rol de cada key, keys de solo lectura, caducidad, rotación sin corte (se
crea otra y se revoca la vieja), límites de peticiones distintos para keys.

## 1. La key, cómo se guarda y cómo se comprueba

### Formato

`efk_<publicId>_<secreto>`

- `publicId`: 12 caracteres `[A-Za-z0-9]` aleatorios, únicos. Identifica la key sin revelarla y es lo
  que se ve en la lista.
- `secreto`: 32 bytes de `SecureRandom` en base64url sin relleno (43 caracteres).
- El valor completo solo se devuelve en la respuesta de creación.

Una cadena que no cumple `^efk_[A-Za-z0-9]{12}_[A-Za-z0-9_-]{43}$` se rechaza sin consultar la base.

### Tabla `api_keys` (migración V16)

```sql
CREATE TABLE api_keys (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 created_by uuid NOT NULL REFERENCES users(id),
 name varchar(80) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 80),
 public_id varchar(12) NOT NULL UNIQUE CHECK (public_id ~ '^[A-Za-z0-9]{12}$'),
 secret_hash varchar(64) NOT NULL CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT now(),
 last_used_at timestamptz,
 revoked_at timestamptz,
 revoked_by uuid REFERENCES users(id),
 CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);
CREATE UNIQUE INDEX uq_api_keys_active_name ON api_keys (tenant_id, lower(name)) WHERE revoked_at IS NULL;
CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id, created_at DESC);

ALTER TABLE audit_events ADD COLUMN api_key_id uuid REFERENCES api_keys(id);
```

Los permisos de los roles de base de datos de la aplicación sobre la tabla nueva siguen el mismo
patrón que las demás (lo comprueba `DatabaseRolesIT`).

- Solo se guarda el SHA-256 en hex del secreto, como en `auth_refresh_tokens`. El secreto tiene 256
  bits de entropía, así que no hace falta un hash lento.
- La comparación del hash usa `MessageDigest.isEqual` (tiempo constante).
- Máximo 10 keys activas por empresa.

### Filtro `ApiKeyAuthenticationFilter`

Se registra en `SecurityConfig` antes de `BearerTokenAuthenticationFilter`. Si la petición no trae
`X-Api-Key`, no hace nada.

| Situación | Respuesta |
|---|---|
| `X-Api-Key` y `Authorization` a la vez | 400 `CREDENTIALS_AMBIGUOUS` |
| Formato inválido, `publicId` inexistente, secreto distinto o key revocada | 401 `UNAUTHENTICATED`, mismo mensaje en todos los casos |
| Key válida | Deja un `ApiKeyAuthentication` en el contexto y sigue |

- `ApiKeyAuthentication` lleva `apiKeyId`, `tenantId` y `createdBy`. Su `getName()` es
  `apikey:<apiKeyId>`, así que `RateLimitFilter` le da un cupo propio sin cambios.
- Los errores salen como problem+json por el mismo `HandlerExceptionResolver` que usa hoy
  `SecurityConfig`.
- `last_used_at` se actualiza con
  `UPDATE api_keys SET last_used_at = now() WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')`,
  como mucho una escritura por minuto y key.

## 2. Qué puede hacer una key y a nombre de quién queda

### Resolución del acceso

`TenantAccess` gana un campo `apiKeyId` (`null` para personas). Cuando la autenticación es
`ApiKeyAuthentication`, `TenantAccessResolver` hace lo siguiente:

1. La empresa es la de la key. `X-Tenant-Id` es opcional; si viene con otra empresa responde 403
   `TENANT_FORBIDDEN`.
2. Comprueba que la empresa esté `ACTIVE` y que `created_by` tenga una membresía activa en ella con un
   rol que factura (`Role.canBill()`). Si no, responde 401 `UNAUTHENTICATED`: la key deja de servir
   mientras esa persona no tenga acceso.
3. Devuelve `TenantAccess(userId = created_by, tenantId, role = BILLER, apiKeyId)`. El rol se queda en
   BILLER aunque el creador sea OWNER o ADMIN.

Como el rol es BILLER, los `requireWrite()` y `requireOwner()` que ya existen bloquean con 403
`ROLE_FORBIDDEN` certificados, establecimientos y puntos de emisión, secuenciales, webhooks, paso a
producción y la gestión de API keys. No hace falta ninguna regla nueva en esos controladores.

Permitido: crear clientes y productos, emitir y reemitir facturas, consultar comprobantes y su
estado, descargar XML y RIDE, reenviar el correo y `GET /api/v1/tenants/current`.

### Endpoints solo para personas

`UserIdentityResolver` responde 403 `API_KEY_NOT_ALLOWED` cuando la autenticación es una API key.
Afecta a los endpoints que reciben `UserIdentity` directamente: `POST /api/v1/tenants`,
`GET /api/v1/tenants`, `GET /api/v1/auth/me`, `POST /api/v1/auth/password` y
`POST /api/v1/auth/email-verification/resend`.
`TenantAccessResolver` resuelve las keys por su propio camino, sin pasar por `UserIdentityResolver`.

### Auditoría

- Firma nueva `AuditLog.record(TenantAccess access, String action, String entityType, UUID entityId, Map<String, Object> metadata)`.
  Escribe `actor_id = access.userId()` y `api_key_id = access.apiKeyId()`.
- Las llamadas actuales `audit.record(access.tenantId(), access.userId(), …)` pasan a la firma nueva.
- La firma con `tenantId` y `actorId` se queda para quien no tiene `TenantAccess` (alta de empresa,
  workers).
- `created_by`, `requested_by` y similares siguen guardando a la persona que creó la key.
- Crear y revocar una key se auditan como `API_KEY_CREATED` y `API_KEY_REVOKED` (entidad `API_KEY`).

## 3. Endpoints de gestión y pantalla web

### API

Nuevo `ApiKeyController` en `identity`. Exige `TenantAccess.requireWrite()` (OWNER o ADMIN), así que
una key no puede usarlo.

| Método y ruta | Cuerpo | Respuesta |
|---|---|---|
| `POST /api/v1/api-keys` | `{ "name": "ERP Contable" }` | 201 `{ id, name, publicId, key, createdAt }` |
| `GET /api/v1/api-keys` | — | 200 `[{ id, name, publicId, createdBy, createdAt, lastUsedAt, revokedAt }]` |
| `DELETE /api/v1/api-keys/{id}` | — | 204 |

- `name`: se recorta y debe tener de 1 a 80 caracteres (400 `VALIDATION_FAILED` si no).
- Errores al crear: 409 `API_KEY_LIMIT` si ya hay 10 activas; 409 `API_KEY_NAME_TAKEN` si hay una
  activa con el mismo nombre (sin distinguir mayúsculas).
- `createdBy` en la lista es el correo del creador.
- La lista incluye las revocadas, primero las activas y luego por `createdAt` descendente.
- `DELETE` de una key ya revocada responde 204 sin cambiar nada. Una key que no existe o es de otra
  empresa responde 404 `API_KEY_NOT_FOUND`.

`contracts/openapi.yaml`:

- Nuevo esquema de seguridad `apiKey` (`type: apiKey`, `in: header`, `name: X-Api-Key`).
- Las operaciones que acepta una key declaran `security: [bearerAuth: [], apiKey: []]`. Las de solo
  personas se quedan con `bearerAuth`.
- Se añaden los tres endpoints nuevos y los errores `CREDENTIALS_AMBIGUOUS` y `API_KEY_NOT_ALLOWED`.

### Web (`client-factura-facil`)

Nueva sección **API keys** en la página Integraciones, encima de los webhooks:

- Una tabla con nombre, `efk_<publicId>_…`, creada por, fecha de creación y último uso ("Nunca" si no
  se ha usado). Las revocadas aparecen atenuadas, con su fecha de revocación.
- Un formulario de una línea con el nombre y el botón "Crear API key".
- Al crearla, un aviso con el valor completo y el botón Copiar, reutilizando `SecretNotice`, con el
  texto "Guárdala ahora: no volverás a verla".
- "Revocar" pide confirmación: "Los sistemas que usen esta key dejarán de poder facturar".
- Una ayuda breve con un ejemplo de `curl` que envía `X-Api-Key` a `GET /api/v1/tenants/current`.
- La sección solo se muestra a OWNER y ADMIN.
- La copia de `contracts/openapi.yaml` y `src/api/schema.ts` se actualizan en la misma rama.

## 4. Pruebas

### API (Testcontainers, como el resto de `*IT`)

- `ApiKeyApiIT`:
  - crear, listar y revocar;
  - `key` solo aparece al crear;
  - límite de 10 keys y nombre repetido;
  - 404 con una key de otra empresa;
  - BILLER y AUDITOR reciben 403 al gestionar keys;
  - una key recibe 403 al gestionar keys;
  - auditoría `API_KEY_CREATED` y `API_KEY_REVOKED`.
- `ApiKeyAuthenticationIT`:
  - una key válida emite una factura y la consulta;
  - key revocada, inexistente o mal formada: 401 con el mismo cuerpo;
  - `X-Api-Key` junto con `Authorization`: 400 `CREDENTIALS_AMBIGUOUS`;
  - `X-Tenant-Id` de otra empresa: 403;
  - endpoints de configuración: 403 `ROLE_FORBIDDEN`;
  - crear o listar empresas: 403 `API_KEY_NOT_ALLOWED`;
  - empresa suspendida: 401;
  - la auditoría guarda `actor_id` y `api_key_id`;
  - `last_used_at` no se reescribe dentro del mismo minuto.
- `RateLimitIT`: la key tiene un cupo separado del de su creador.
- `OpenApiContractIT`: el esquema `apiKey` y los endpoints nuevos coinciden con los controladores.
- `DatabaseRolesIT`: los permisos sobre `api_keys`.
- Pruebas unitarias del formato: generar, separar y rechazar longitudes y caracteres inválidos.

### Web (Vitest, junto a `integraciones/page.test.tsx`)

- La lista se muestra.
- Crear muestra el valor con el botón Copiar.
- Revocar pide confirmación y actualiza la fila.
- La sección no aparece para BILLER ni AUDITOR.

### Navegador (`e2e/`)

1. El dueño crea una key en Integraciones.
2. Con `fetch` desde Node y `X-Api-Key`, la prueba emite una factura que llega a Autorizada contra el
   SRI simulado.
3. El dueño revoca la key y la siguiente llamada responde 401.

## Integración

- Misma rama `p11-api-keys` en los dos repos, para que el CI de la API pruebe en el navegador con la
  web de esa rama.
- Los PR van hacia `dev` cuando `p10-autenticacion-propia` esté fusionada en `dev`.
