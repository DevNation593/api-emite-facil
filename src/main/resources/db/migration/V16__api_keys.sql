-- API keys: credenciales para que otros sistemas (ERP, punto de venta, tienda en línea) llamen a la
-- API sin el inicio de sesión de una persona. De la key solo se guarda el publicId y el SHA-256 de su
-- secreto; el valor completo se entrega una vez, al crearla. Se revocan, no caducan. La auditoría
-- anota además qué key hizo cada cosa.
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
-- Dos keys activas de una empresa no comparten nombre (sin distinguir mayúsculas); las revocadas sí.
CREATE UNIQUE INDEX uq_api_keys_active_name ON api_keys (tenant_id, lower(name)) WHERE revoked_at IS NULL;
CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id, created_at DESC);

ALTER TABLE audit_events ADD COLUMN api_key_id uuid REFERENCES api_keys(id);
