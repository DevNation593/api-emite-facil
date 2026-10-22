-- Datos del RIDE que antes no se guardaban: el teléfono del emisor; ese teléfono y el del receptor, congelados
-- en el comprobante; y el plazo de cada forma de pago (plazo y unidadTiempo del XML). Todos opcionales: los
-- comprobantes anteriores quedan como estaban.

ALTER TABLE taxpayers ADD COLUMN phone text;

ALTER TABLE fiscal_documents
 ADD COLUMN issuer_phone text,
 ADD COLUMN customer_phone text;

-- El XSD admite hasta 14 dígitos con 2 decimales en el plazo y de 1 a 10 caracteres en la unidad.
ALTER TABLE fiscal_document_payments
 ADD COLUMN term numeric(14,2) CHECK (term >= 0),
 ADD COLUMN time_unit varchar(10) CHECK (char_length(time_unit) >= 1),
 ADD CONSTRAINT fiscal_document_payments_term_complete CHECK ((term IS NULL) = (time_unit IS NULL));
