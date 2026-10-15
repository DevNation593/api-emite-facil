-- Autenticación propia: la API guarda las cuentas (correo y contraseña) y emite sus tokens, en lugar
-- de aceptar los de un proveedor de identidad externo. Las invitaciones desaparecen: cada usuario
-- trabaja en las empresas que crea.
DROP TABLE invitations;

-- Un usuario ya no es el par (emisor, sujeto) de un proveedor: es una cuenta con correo. Los que
-- existían se conservan (facturas y auditoría los citan) sin contraseña; con correo pueden ponerse
-- una con la recuperación de contraseña.
ALTER TABLE users DROP COLUMN issuer, DROP COLUMN subject;
UPDATE users SET email = lower(btrim(email)) WHERE email IS NOT NULL;
UPDATE users SET email = NULL WHERE email = '';
-- Si dos proveedores dieron el mismo correo, solo el usuario más antiguo se queda con él.
UPDATE users u SET email = NULL
 WHERE email IS NOT NULL
   AND EXISTS (SELECT 1 FROM users o WHERE o.email = u.email AND (o.created_at, o.id) < (u.created_at, u.id));
ALTER TABLE users
 ADD COLUMN password_hash text,
 ADD COLUMN email_verified_at timestamptz,
 ADD CONSTRAINT users_email_normalized CHECK (email = lower(email) AND length(email) BETWEEN 3 AND 254),
 ADD CONSTRAINT users_password_needs_email CHECK (password_hash IS NULL OR email IS NOT NULL);
CREATE UNIQUE INDEX uq_users_email ON users (email);

-- Una sesión es un inicio de sesión: dura hasta expires_at aunque se renueve, y se cierra al salir,
-- al cambiar la contraseña o si alguien presenta un token de renovación ya usado.
CREATE TABLE auth_sessions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,
 last_used_at timestamptz NOT NULL DEFAULT now(),
 revoked_at timestamptz,
 revoke_reason varchar(30) CHECK (revoke_reason IN ('LOGOUT','PASSWORD_CHANGED','PASSWORD_RESET','TOKEN_REUSED')),
 CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_auth_sessions_expires ON auth_sessions (expires_at);

-- Tokens de renovación de cada sesión. Cada uso entrega uno nuevo y marca el anterior: presentar
-- otra vez uno marcado delata que alguien más lo tiene. Solo se guarda su SHA-256.
CREATE TABLE auth_refresh_tokens (
 token_hash varchar(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 session_id uuid NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT now(),
 used_at timestamptz
);
CREATE INDEX idx_auth_refresh_tokens_session ON auth_refresh_tokens (session_id);

-- Enlaces de un solo uso que llegan por correo: confirmar el correo y cambiar la contraseña.
CREATE TABLE user_tokens (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id),
 purpose varchar(20) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
 token_hash varchar(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,
 used_at timestamptz
);
CREATE INDEX idx_user_tokens_user ON user_tokens (user_id, purpose) WHERE used_at IS NULL;
CREATE INDEX idx_user_tokens_expires ON user_tokens (expires_at);
