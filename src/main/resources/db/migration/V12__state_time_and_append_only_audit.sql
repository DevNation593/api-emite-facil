-- Desde cuándo está cada comprobante en su estado actual. Las métricas de operación medían la
-- antigüedad desde su creación, y un comprobante antiguo recién movido parecía atascado.
ALTER TABLE fiscal_documents ADD COLUMN state_changed_at timestamptz;
UPDATE fiscal_documents d
   SET state_changed_at = coalesce((SELECT max(e.event_time) FROM state_events e WHERE e.document_id = d.id), d.created_at);
ALTER TABLE fiscal_documents ALTER COLUMN state_changed_at SET NOT NULL;
ALTER TABLE fiscal_documents ALTER COLUMN state_changed_at SET DEFAULT now();
DROP INDEX idx_fiscal_documents_open;
CREATE INDEX idx_fiscal_documents_open ON fiscal_documents (state, state_changed_at) WHERE state <> 'AUTHORIZED';

-- La bitácora y la historia de estados solo crecen: ni la aplicación ni quien tenga sus credenciales
-- puede reescribirlas o borrarlas sin retirar antes estos disparadores (y con los dos usuarios de
-- producción, infra/postgres/roles.sql, la aplicación no puede retirarlos).
CREATE FUNCTION reject_change_to_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% es de solo inserción', TG_TABLE_NAME USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER audit_events_append_only BEFORE UPDATE OR DELETE ON audit_events
 FOR EACH ROW EXECUTE FUNCTION reject_change_to_append_only();
CREATE TRIGGER audit_events_no_truncate BEFORE TRUNCATE ON audit_events
 FOR EACH STATEMENT EXECUTE FUNCTION reject_change_to_append_only();
CREATE TRIGGER state_events_append_only BEFORE UPDATE OR DELETE ON state_events
 FOR EACH ROW EXECUTE FUNCTION reject_change_to_append_only();
CREATE TRIGGER state_events_no_truncate BEFORE TRUNCATE ON state_events
 FOR EACH STATEMENT EXECUTE FUNCTION reject_change_to_append_only();
