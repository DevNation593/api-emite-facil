-- Un comprobante devuelto o no autorizado no se corrige: se emite otro que lo reemplaza. Aquí queda
-- el vínculo, en el nuevo, hacia el rechazado; el rechazado no cambia.
ALTER TABLE fiscal_documents ADD COLUMN replaces_document_id uuid;
ALTER TABLE fiscal_documents ADD CONSTRAINT fiscal_documents_replaces_fkey
 FOREIGN KEY (tenant_id, replaces_document_id) REFERENCES fiscal_documents (tenant_id, id);
ALTER TABLE fiscal_documents ADD CONSTRAINT fiscal_documents_not_self_replaced CHECK (replaces_document_id <> id);
-- Un rechazado se reemplaza una sola vez.
CREATE UNIQUE INDEX uq_fiscal_documents_replaces ON fiscal_documents (replaces_document_id)
 WHERE replaces_document_id IS NOT NULL;
