-- Identidad y multiempresa (docs/04-database-design.md, docs/10-security.md).
-- Un RUC pertenece a un solo tenant; las claves foráneas compuestas impiden que una fila
-- referencie un recurso de otro tenant.
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
