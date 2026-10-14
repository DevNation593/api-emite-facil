-- Índices para las métricas de operación (OperationalMetrics): cuentan lo que espera o está detenido
-- sin recorrer los comprobantes autorizados ni las entregas enviadas, que son casi todos.
CREATE INDEX idx_fiscal_documents_open ON fiscal_documents (state, created_at) WHERE state <> 'AUTHORIZED';
CREATE INDEX idx_deliveries_open ON deliveries (status) WHERE status <> 'SENT';
