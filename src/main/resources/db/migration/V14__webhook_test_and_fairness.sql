-- Aviso de prueba: un aviso sin comprobante, que solo puede ser de tipo webhook.test.
ALTER TABLE webhook_deliveries ALTER COLUMN document_id DROP NOT NULL;
ALTER TABLE webhook_deliveries ALTER COLUMN document_state DROP NOT NULL;
ALTER TABLE webhook_deliveries ADD CONSTRAINT webhook_deliveries_test_has_no_document
 CHECK ((event_type = 'webhook.test') = (document_id IS NULL) AND (document_id IS NULL) = (document_state IS NULL));

-- Los envíos en curso de cada empresa: se cuentan al reservar un aviso, para no superar el tope.
CREATE INDEX idx_webhook_deliveries_sending ON webhook_deliveries (tenant_id) WHERE status = 'SENDING';

-- La purga del historial busca los avisos terminados más antiguos.
CREATE INDEX idx_webhook_deliveries_finished ON webhook_deliveries (occurred_at) WHERE status IN ('SENT', 'FAILED');
