# 04 — Modelo PostgreSQL y migraciones Flyway

## Principios
Todos los registros de negocio llevan `tenant_id`; documentos fiscales también llevan `taxpayer_id`, `environment`, `document_type`, `schema_version`. IDs UUID; dinero `NUMERIC(18,6)` para cálculos internos con redondeo final por XSD; horas `TIMESTAMPTZ`; secuenciales `INTEGER/BIGINT`, representados como cadenas de 9 dígitos al serializar. Restricciones de unicidad, claves foráneas, índices compuestos y versionado optimista.

## Modelo lógico
| Tabla | Campos clave |
|---|---|
| tenants | id, name, plan, status |
| users / memberships | user_id, tenant_id, role, status |
| taxpayers | id, tenant_id, ruc (único global), legal_name, provider_ruc, default_environment |
| establishments | id, taxpayer_id, code(3), address |
| emission_points | id, establishment_id, code(3), enabled |
| certificates | id, taxpayer_id, object_key, encrypted_secret_ref, valid_from/to, fingerprint |
| customers | id, tenant_id, id_type, id_number, name, email |
| products | id, tenant_id, sku, name, price, tax_profile_id |
| tax_rules | id, code, rate, valid_from/to, applicability_jsonb |
| document_sequences | taxpayer_id, environment, type, establishment_code, point_code, next_value |
| fiscal_documents | id, tenant_id, taxpayer_id, type, version, environment, sequence, access_key, state, total, issue_date, related_document_id |
| fiscal_document_lines | id, document_id, line_no, sku, quantity, unit_price, discount, tax_jsonb, totals |
| document_artifacts | id, document_id, kind, sha256, object_key, created_at |
| sri_attempts | id, document_id, action, request_hash, raw_response_object_key, status, error_code, occurred_at |
| state_events | id, document_id, previous_state, new_state, reason, event_time |
| idempotency_records | tenant_id, key, payload_hash, response_ref, expires_at |
| outbox_events | id, aggregate_id, type, payload_jsonb, created_at, dispatched_at |
| deliveries | id, document_id, recipient, channel, provider_message_id, status, attempts |
| audit_events | id, tenant_id, actor_id, action, entity_id, metadata_jsonb, created_at |

## Migraciones reales
El esquema vigente son las migraciones de `src/main/resources/db/migration`: `V1__schema_registry.sql` y `V2__identity_tenancy.sql` (`tenants`, `users`, `memberships`, `taxpayers`, `establishments`, `emission_points`, `audit_events`). Difieren del esqueleto de abajo en dos puntos deliberados: usan `varchar` con `CHECK` en lugar de `char(n)` para que Hibernate valide el esquema sin anotaciones especiales, y `establishments` y `emission_points` llevan `tenant_id` con clave foránea compuesta hacia su padre. `users` identifica a cada persona por `(issuer, subject)` del proveedor OIDC y `memberships` tiene `id` propio con `UNIQUE (user_id, tenant_id)`. `V3__catalogs_billing.sql` añade los catálogos y la facturación:

- **Catálogos** `identification_types`, `tax_rates` y `payment_methods`, sembrados desde la ficha técnica v2.34 (tablas 6, 17 y 24). `identification_types` lleva además el formato exigido a cada tipo y la marca de consumidor final.
- **`customers` y `products`**, únicos por tenant en identificación y en código respectivamente; el producto referencia una tarifa de IVA del catálogo.
- **`document_sequences`**: un contador por punto de emisión, ambiente y tipo de documento (no por RUC y códigos como en el esqueleto: el punto de emisión ya determina emisor y establecimiento). `next_value` es el próximo número y `last_issue_date` sostiene el orden cronológico.
- **`fiscal_documents`** con sus líneas, totales por tarifa (`fiscal_document_taxes`) y pagos. El documento congela RUC del emisor, códigos, identificación, nombre, dirección y correo del cliente, y código, descripción y tarifa de cada línea. `schema_version` queda nulo hasta generar el XML. No existe el estado `DRAFT`: `sequence` y `access_key` son obligatorios.
- **`state_events`**, **`idempotency_records`** (clave primaria `(tenant_id, idempotency_key)`) y **`outbox_events`** (payload solo con identificadores).
- **Transmisión al SRI (`V5__sri_transmission.sql`).** `fiscal_documents` gana `next_attempt_at` (cuándo debe retomarse; nulo si no hay nada programado), `attempt_count` (intentos no concluyentes desde el último cambio de estado), `authorization_number` y `authorized_at`. `sri_attempts` guarda cada llamada al SRI: operación (`RECEPTION` o `AUTHORIZATION`), ambiente, inicio y fin, cómo se interpretó (`outcome`, nulo mientras está en curso), estado literal del SRI, código HTTP, detalle del fallo y la respuesta cruda en la propia tabla (no hay almacenamiento de objetos todavía). `sri_messages` guarda los mensajes de cada intento en su orden. `document_artifacts` admite además el tipo `SRI_AUTHORIZATION`.
- **Archivo y entrega (`V6__archive_delivery.sql`).** `document_artifacts` admite `XML_AUTHORIZED` y `RIDE_PDF`, y pasa a ser de solo inserción por disparadores (`document_artifacts_immutable` y `document_artifacts_no_truncate`): modificar o borrar un artefacto exige retirar antes el disparador, lo que solo puede hacer el dueño de la tabla. `deliveries` guarda cada entrega: canal (`EMAIL`), destinatario, estado (`PENDING`, `SENDING`, `SENT`, `FAILED`), intentos, `next_attempt_at`, `sending_since` (inicio del envío en curso), último error, identificador del mensaje, quién la pidió (nulo si es la automática) y fecha de envío. Un índice único parcial garantiza una sola entrega automática por comprobante y canal. El contenido de los correos no se guarda.
- **Equipo e invitaciones (`V10__team_invitations.sql`).** `invitations` guarda cada invitación a una empresa: correo (en minúsculas), rol, quién invitó, caducidad, y cuándo y por quién se aceptó o cuándo se revocó. Del código solo está su SHA-256 (`token_hash`, único). Un índice único parcial deja una sola invitación sin aceptar ni revocar por empresa y correo.
- **Reemplazo de comprobantes (`V11__document_replacement.sql`).** `fiscal_documents.replaces_document_id` enlaza una factura con el comprobante fallido al que reemplaza (misma empresa, nunca a sí misma); un índice único parcial impide reemplazar dos veces el mismo.
- **Webhooks (`V13__webhooks.sql`).** `webhook_subscriptions` guarda a qué dirección avisa cada empresa, de qué eventos, su estado (activa o apagada, y por qué) y su secreto de firma cifrado con la clave maestra (más el anterior, mientras dura el solape tras rotarlo). `webhook_deliveries` tiene una fila por evento y suscripción, con el mismo `event_id` para todas las suscripciones de un evento, su estado de envío, los intentos y el código de la última respuesta; se borra con su suscripción. No hay tabla de eventos ni se guarda el cuerpo de los avisos: se arma al enviar.
- **Tiempo en el estado y bitácoras de solo inserción (`V12__state_time_and_append_only_audit.sql`).** `fiscal_documents.state_changed_at` dice desde cuándo está el comprobante en su estado actual (lo usan las métricas de operación). `audit_events` y `state_events` pasan a ser de solo inserción por disparadores, como ya lo era `document_artifacts`.

## Migración ejemplo `V1__initial_core.sql` (esqueleto útil, NO esquema final completo)
```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE tenants (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 name text NOT NULL, status text NOT NULL DEFAULT 'ACTIVE'
);
CREATE TABLE taxpayers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 ruc char(13) NOT NULL UNIQUE, legal_name text NOT NULL,
 provider_ruc char(13), UNIQUE(tenant_id,id)
);
CREATE TABLE document_sequences (
 taxpayer_id uuid NOT NULL REFERENCES taxpayers(id),
 environment char(1) NOT NULL CHECK (environment IN ('1','2')),
 document_type char(2) NOT NULL,
 establishment_code char(3) NOT NULL,
 emission_point_code char(3) NOT NULL,
 next_value integer NOT NULL CHECK (next_value BETWEEN 1 AND 999999999),
 PRIMARY KEY (taxpayer_id,environment,document_type,establishment_code,emission_point_code)
);
CREATE TABLE fiscal_documents (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 taxpayer_id uuid NOT NULL,
 document_type char(2) NOT NULL,
 schema_version varchar(12) NOT NULL,
 environment char(1) NOT NULL CHECK (environment IN ('1','2')),
 establishment_code char(3) NOT NULL, emission_point_code char(3) NOT NULL,
 sequence integer CHECK (sequence BETWEEN 1 AND 999999999),
 access_key char(49) UNIQUE,
 state varchar(40) NOT NULL, issue_date date NOT NULL,
 total numeric(18,6) NOT NULL, version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (tenant_id,taxpayer_id) REFERENCES taxpayers(tenant_id,id),
 UNIQUE(taxpayer_id,environment,document_type,establishment_code,emission_point_code,sequence),
 CHECK ((state = 'DRAFT') = (sequence IS NULL)),
 CHECK ((sequence IS NULL) = (access_key IS NULL))
);
CREATE INDEX idx_fiscal_documents_tenant_state ON fiscal_documents(tenant_id,state,created_at DESC);
CREATE TABLE outbox_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 aggregate_id uuid NOT NULL, event_type text NOT NULL,
 payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 dispatched_at timestamptz
);
```

## Unicidad fiscal y borradores
- **Un RUC, un tenant.** `taxpayers.ruc` es único en toda la plataforma. Si dos tenants pudieran registrar el mismo RUC tendrían contadores independientes y emitirían el mismo secuencial para el mismo RUC/establecimiento/punto; la unicidad de `access_key` no lo detectaría porque el código numérico es aleatorio. El alta de un RUC ya registrado se rechaza con `409` y se deriva a soporte; la titularidad se acredita con el certificado del emisor.
- **Pertenencia.** La clave foránea compuesta `(tenant_id, taxpayer_id)` impide que un documento referencie un emisor de otro tenant.
- **Borradores.** Un documento en `DRAFT` no tiene `sequence` ni `access_key`; ambos se asignan juntos en la transición a `RESERVED` y desde entonces son inmutables. Las dos restricciones `CHECK` lo garantizan. `POST /invoices` crea el documento directamente en `RESERVED`; `DRAFT` queda para el editor del frontend.
- **Secuencial inicial.** `next_value` se configura al dar de alta el punto de emisión: un emisor que ya facturaba con otro sistema continúa su numeración.

## Reserva de secuenciales
Dentro de **una misma transacción PostgreSQL**, `SELECT ... FOR UPDATE` sobre fila de `document_sequences`; leer `next_value`, validar límite, incrementar, persistir documento y outbox. Alternativa `UPDATE ... RETURNING`. La reserva puede producir huecos si se aborta/cancela una emisión, y no debe reutilizarse indebidamente; política fiscal confirmada con SRI. En JPA usar `@Lock(PESSIMISTIC_WRITE)` sobre la fila y `@Transactional`.

## Tenant isolation
No confiar en `tenantId` del body. Resolver desde token autorizado y filtrar todas las queries por ámbito; defensas opcionales PostgreSQL RLS. Tests de fuga cruzada en endpoints, jobs y URLs presignadas. Migraciones Flyway inmutables y pruebas de restore.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
