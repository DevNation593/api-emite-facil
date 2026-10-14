-- Transmisión al SRI: programación de reintentos, datos de la autorización y bitácora de intentos.

-- next_attempt_at: cuándo debe volver a tomarse el documento (nulo = nada programado).
-- attempt_count: intentos no concluyentes ante el SRI desde el último cambio de estado.
ALTER TABLE fiscal_documents
 ADD COLUMN next_attempt_at timestamptz,
 ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
 ADD COLUMN authorization_number varchar(49),
 ADD COLUMN authorized_at timestamptz;
CREATE INDEX idx_fiscal_documents_due ON fiscal_documents (next_attempt_at) WHERE next_attempt_at IS NOT NULL;

-- Cada llamada a los servicios del SRI. outcome es nulo mientras la llamada está en curso;
-- sri_status es el estado tal como lo devolvió el SRI.
CREATE TABLE sri_attempts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 operation varchar(20) NOT NULL CHECK (operation IN ('RECEPTION','AUTHORIZATION')),
 environment varchar(1) NOT NULL CHECK (environment IN ('1','2')),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 completed_at timestamptz,
 outcome varchar(30),
 sri_status varchar(60),
 http_status integer,
 error_detail text,
 response bytea,
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id)
);
CREATE INDEX idx_sri_attempts_document ON sri_attempts (document_id, started_at);

-- Mensajes (errores y advertencias) que el SRI devolvió en un intento, en su orden.
CREATE TABLE sri_messages (
 attempt_id uuid NOT NULL REFERENCES sri_attempts(id),
 position integer NOT NULL,
 identifier varchar(20),
 message text,
 additional_info text,
 type varchar(30),
 PRIMARY KEY (attempt_id, position)
);

-- La respuesta de autorización del SRI se conserva íntegra como artefacto del comprobante.
ALTER TABLE document_artifacts DROP CONSTRAINT document_artifacts_kind_check;
ALTER TABLE document_artifacts ADD CONSTRAINT document_artifacts_kind_check
 CHECK (kind IN ('XML_UNSIGNED','XML_SIGNED','SRI_AUTHORIZATION'));
