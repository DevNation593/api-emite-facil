-- Sobre común para todos los comprobantes: factura (01), liquidación de compra (03), notas de crédito (04) y
-- de débito (05), guía de remisión (06) y retención (07).
-- Las columnas customer_* conservan su nombre pero significan "receptor": comprador en factura y notas,
-- proveedor en la liquidación, sujeto retenido en la retención y transportista en la guía.
-- En la retención, total es el total retenido; la guía de remisión no lleva total.
-- content guarda lo propio de cada tipo (la factura no lo usa: sus líneas, impuestos y pagos tienen tablas).

ALTER TABLE fiscal_documents
 ADD CONSTRAINT fiscal_documents_type_check CHECK (document_type IN ('01','03','04','05','06','07')),
 ALTER COLUMN subtotal DROP NOT NULL,
 ALTER COLUMN total_discount DROP NOT NULL,
 ALTER COLUMN total_tax DROP NOT NULL,
 ALTER COLUMN total DROP NOT NULL,
 ADD COLUMN content jsonb,
 ADD COLUMN modified_document_id uuid,
 ADD COLUMN modified_doc_code varchar(2) CHECK (modified_doc_code ~ '^[0-9]{2}$'),
 ADD COLUMN modified_doc_number varchar(17) CHECK (modified_doc_number ~ '^[0-9]{3}-[0-9]{3}-[0-9]{9}$'),
 ADD COLUMN modified_doc_date date,
 ADD CONSTRAINT fiscal_documents_total_by_type CHECK ((document_type = '06') = (total IS NULL)),
 ADD CONSTRAINT fiscal_documents_invoice_totals CHECK (document_type <> '01'
  OR (subtotal IS NOT NULL AND total_discount IS NOT NULL AND total_tax IS NOT NULL)),
 ADD CONSTRAINT fiscal_documents_content_by_type CHECK ((document_type = '01') = (content IS NULL)),
 ADD CONSTRAINT fiscal_documents_modified_complete CHECK (
  (modified_doc_code IS NULL) = (modified_doc_number IS NULL)
  AND (modified_doc_code IS NULL) = (modified_doc_date IS NULL)),
 ADD CONSTRAINT fiscal_documents_modified_id_needs_data CHECK (
  modified_document_id IS NULL OR modified_doc_code IS NOT NULL),
 ADD CONSTRAINT fiscal_documents_modified_document_fkey
  FOREIGN KEY (tenant_id, modified_document_id) REFERENCES fiscal_documents (tenant_id, id),
 ADD CONSTRAINT fiscal_documents_modified_not_self CHECK (modified_document_id <> id);

CREATE INDEX idx_fiscal_documents_modified ON fiscal_documents (modified_document_id)
 WHERE modified_document_id IS NOT NULL;
