-- Invitaciones a una empresa. El código del enlace no se guarda: solo su SHA-256, así que ni quien
-- lea la base puede usar una invitación ajena. Una invitación sirve una vez, para un correo, y caduca.
CREATE TABLE invitations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 email text NOT NULL CHECK (email = lower(email) AND length(email) BETWEEN 3 AND 254),
 -- El rol de propietario no se concede por invitación.
 role varchar(20) NOT NULL CHECK (role IN ('ADMIN','BILLER','AUDITOR')),
 token_hash varchar(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 invited_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,
 accepted_at timestamptz,
 accepted_by uuid REFERENCES users(id),
 revoked_at timestamptz,
 CHECK ((accepted_at IS NULL) = (accepted_by IS NULL)),
 CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);
-- Una sola invitación pendiente por empresa y correo: invitar de nuevo reemplaza la anterior.
CREATE UNIQUE INDEX uq_invitations_pending ON invitations (tenant_id, email)
 WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX idx_memberships_tenant ON memberships (tenant_id, created_at);
