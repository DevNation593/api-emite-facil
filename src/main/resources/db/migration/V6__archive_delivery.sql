-- Archivo del comprobante autorizado y entrega al receptor.

-- Nuevos artefactos: el XML autorizado que se entrega al cliente y el RIDE en PDF.
ALTER TABLE document_artifacts DROP CONSTRAINT document_artifacts_kind_check;
ALTER TABLE document_artifacts ADD CONSTRAINT document_artifacts_kind_check
 CHECK (kind IN ('XML_UNSIGNED','XML_SIGNED','SRI_AUTHORIZATION','XML_AUTHORIZED','RIDE_PDF'));

-- Los artefactos son de solo inserción: ni la aplicación ni quien tenga acceso de escritura a la
-- tabla puede modificar o borrar un XML o un RIDE ya guardado sin retirar antes estos disparadores.
CREATE FUNCTION reject_artifact_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'document_artifacts es de solo inserción' USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER document_artifacts_immutable BEFORE UPDATE OR DELETE ON document_artifacts
 FOR EACH ROW EXECUTE FUNCTION reject_artifact_change();
CREATE TRIGGER document_artifacts_no_truncate BEFORE TRUNCATE ON document_artifacts
 FOR EACH STATEMENT EXECUTE FUNCTION reject_artifact_change();

-- Entregas de un comprobante al receptor. Tienen su propio estado: un fallo de entrega nunca
-- cambia el estado fiscal del comprobante.
CREATE TABLE deliveries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 channel varchar(20) NOT NULL CHECK (channel IN ('EMAIL')),
 recipient text NOT NULL,
 status varchar(20) NOT NULL CHECK (status IN ('PENDING','SENDING','SENT','FAILED')),
 attempt_count integer NOT NULL DEFAULT 0,
 -- Cuándo debe retomarse; nulo si no hay nada programado.
 next_attempt_at timestamptz,
 -- Tras un fallo temporal, la entrega no se reintenta antes de este momento.
 retry_after timestamptz,
 -- Inicio del envío en curso; pasado el plazo de envío se considera abandonado.
 sending_since timestamptz,
 last_error text,
 provider_message_id text,
 -- Nulo: entrega automática al autorizarse el comprobante.
 requested_by uuid REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 sent_at timestamptz,
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id)
);
CREATE INDEX idx_deliveries_document ON deliveries (document_id, created_at);
CREATE INDEX idx_deliveries_due ON deliveries (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
-- Una sola entrega automática por comprobante y canal.
CREATE UNIQUE INDEX uq_deliveries_automatic ON deliveries (document_id, channel) WHERE requested_by IS NULL;
