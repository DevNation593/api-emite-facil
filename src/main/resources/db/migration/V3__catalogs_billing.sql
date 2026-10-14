-- Catálogos tributarios, clientes, productos, secuenciales y documentos fiscales
-- (docs/04-database-design.md). Los valores de catálogo provienen de la Ficha técnica de
-- comprobantes electrónicos esquema off-line v2.34 (julio 2026): tablas 6, 17 y 24.

-- Tabla 6: tipo de identificación del comprador. id_number_pattern es el formato que la plataforma
-- exige a la identificación de cada tipo (decisión propia: solo formato, sin dígito verificador);
-- final_consumer marca el tipo sujeto al monto máximo de venta a consumidor final (ficha, 9.10).
CREATE TABLE identification_types (
 code varchar(2) PRIMARY KEY,
 name text NOT NULL,
 id_number_pattern text NOT NULL,
 final_consumer boolean NOT NULL DEFAULT false
);
INSERT INTO identification_types (code, name, id_number_pattern, final_consumer) VALUES
 ('04', 'RUC', '[0-9]{13}', false),
 ('05', 'CÉDULA', '[0-9]{10}', false),
 ('06', 'PASAPORTE', '[A-Za-z0-9]{3,20}', false),
 ('07', 'VENTA A CONSUMIDOR FINAL', '9{13}', true),
 ('08', 'IDENTIFICACIÓN DEL EXTERIOR', '[A-Za-z0-9]{3,20}', false);

-- Tabla 24: formas de pago, con su fecha de inicio. Ninguna tiene fecha de fin.
CREATE TABLE payment_methods (
 code varchar(2) PRIMARY KEY,
 name text NOT NULL,
 valid_from date NOT NULL,
 valid_to date
);
INSERT INTO payment_methods (code, name, valid_from) VALUES
 ('01', 'SIN UTILIZACION DEL SISTEMA FINANCIERO', DATE '2013-01-01'),
 ('15', 'COMPENSACIÓN DE DEUDAS', DATE '2013-01-01'),
 ('16', 'TARJETA DE DÉBITO', DATE '2016-06-01'),
 ('17', 'DINERO ELECTRÓNICO', DATE '2016-06-01'),
 ('18', 'TARJETA PREPAGO', DATE '2016-06-01'),
 ('19', 'TARJETA DE CRÉDITO', DATE '2016-06-01'),
 ('20', 'OTROS CON UTILIZACIÓN DEL SISTEMA FINANCIERO', DATE '2016-06-01'),
 ('21', 'ENDOSO DE TÍTULOS', DATE '2016-06-01');

-- Tabla 17: tarifa del IVA (impuesto código 2 de la tabla 16). El código 8 es el IVA diferenciado
-- del 8 % que un decreto puede aplicar al sector turístico.
CREATE TABLE tax_rates (
 tax_code varchar(2) NOT NULL,
 rate_code varchar(4) NOT NULL,
 description text NOT NULL,
 rate numeric(5,2) NOT NULL CHECK (rate >= 0),
 PRIMARY KEY (tax_code, rate_code)
);
INSERT INTO tax_rates (tax_code, rate_code, description, rate) VALUES
 ('2', '0', '0%', 0),
 ('2', '2', '12%', 12),
 ('2', '3', '14%', 14),
 ('2', '4', '15%', 15),
 ('2', '5', '5%', 5),
 ('2', '6', 'No Objeto de Impuesto', 0),
 ('2', '7', 'Exento de IVA', 0),
 ('2', '8', 'IVA diferenciado', 8),
 ('2', '10', '13%', 13);

-- Permite claves foráneas compuestas hacia un punto de emisión del mismo tenant.
ALTER TABLE emission_points ADD CONSTRAINT emission_points_tenant_id_id_key UNIQUE (tenant_id, id);

CREATE TABLE customers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 id_type varchar(2) NOT NULL REFERENCES identification_types(code),
 id_number varchar(20) NOT NULL,
 name text NOT NULL,
 email text,
 address text,
 phone text,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT customers_identification_key UNIQUE (tenant_id, id_type, id_number),
 UNIQUE (tenant_id, id)
);

CREATE TABLE products (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 sku varchar(25) NOT NULL,
 name text NOT NULL,
 unit_price numeric(18,6) NOT NULL CHECK (unit_price >= 0),
 iva_tax_code varchar(2) NOT NULL DEFAULT '2',
 iva_rate_code varchar(4) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (iva_tax_code, iva_rate_code) REFERENCES tax_rates (tax_code, rate_code),
 CONSTRAINT products_sku_key UNIQUE (tenant_id, sku),
 UNIQUE (tenant_id, id)
);

-- Un contador por punto de emisión, ambiente y tipo de documento. next_value es el próximo
-- secuencial a entregar; 1000000000 significa agotado. last_issue_date sostiene el orden cronológico.
CREATE TABLE document_sequences (
 tenant_id uuid NOT NULL,
 emission_point_id uuid NOT NULL,
 environment varchar(1) NOT NULL CHECK (environment IN ('1','2')),
 document_type varchar(2) NOT NULL,
 next_value bigint NOT NULL DEFAULT 1 CHECK (next_value BETWEEN 1 AND 1000000000),
 last_issue_date date,
 PRIMARY KEY (emission_point_id, environment, document_type),
 FOREIGN KEY (tenant_id, emission_point_id) REFERENCES emission_points (tenant_id, id)
);

-- El documento congela los datos del emisor y del cliente con los que se emitió.
-- schema_version se fija al generar el XML.
CREATE TABLE fiscal_documents (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 taxpayer_id uuid NOT NULL,
 emission_point_id uuid NOT NULL,
 customer_id uuid NOT NULL,
 document_type varchar(2) NOT NULL,
 schema_version varchar(12),
 environment varchar(1) NOT NULL CHECK (environment IN ('1','2')),
 issuer_ruc varchar(13) NOT NULL,
 establishment_code varchar(3) NOT NULL,
 emission_point_code varchar(3) NOT NULL,
 sequence integer NOT NULL CHECK (sequence BETWEEN 1 AND 999999999),
 access_key varchar(49) NOT NULL UNIQUE CHECK (access_key ~ '^[0-9]{49}$'),
 state varchar(40) NOT NULL,
 issue_date date NOT NULL,
 currency varchar(3) NOT NULL,
 customer_id_type varchar(2) NOT NULL,
 customer_id_number varchar(20) NOT NULL,
 customer_name text NOT NULL,
 customer_address text,
 customer_email text,
 subtotal numeric(14,2) NOT NULL,
 total_discount numeric(14,2) NOT NULL,
 total_tax numeric(14,2) NOT NULL,
 total numeric(14,2) NOT NULL,
 created_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0,
 FOREIGN KEY (tenant_id, taxpayer_id) REFERENCES taxpayers (tenant_id, id),
 FOREIGN KEY (tenant_id, emission_point_id) REFERENCES emission_points (tenant_id, id),
 FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id),
 CONSTRAINT fiscal_documents_sequence_key UNIQUE (emission_point_id, environment, document_type, sequence),
 UNIQUE (tenant_id, id)
);
CREATE INDEX idx_fiscal_documents_tenant_created ON fiscal_documents (tenant_id, created_at DESC, id);

CREATE TABLE fiscal_document_lines (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 line_no integer NOT NULL CHECK (line_no >= 1),
 product_id uuid,
 main_code varchar(25) NOT NULL,
 description text NOT NULL,
 quantity numeric(18,6) NOT NULL CHECK (quantity > 0),
 unit_price numeric(18,6) NOT NULL CHECK (unit_price >= 0),
 discount numeric(14,2) NOT NULL CHECK (discount >= 0),
 subtotal numeric(14,2) NOT NULL CHECK (subtotal >= 0),
 tax_code varchar(2) NOT NULL,
 tax_rate_code varchar(4) NOT NULL,
 tax_rate numeric(5,2) NOT NULL,
 tax_amount numeric(14,2) NOT NULL,
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id),
 UNIQUE (document_id, line_no)
);

-- Totales por tarifa, tal como irán en totalConImpuestos.
CREATE TABLE fiscal_document_taxes (
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 tax_code varchar(2) NOT NULL,
 rate_code varchar(4) NOT NULL,
 rate numeric(5,2) NOT NULL,
 base numeric(14,2) NOT NULL,
 amount numeric(14,2) NOT NULL,
 PRIMARY KEY (document_id, tax_code, rate_code),
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id)
);

CREATE TABLE fiscal_document_payments (
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 line_no integer NOT NULL CHECK (line_no >= 1),
 method_code varchar(2) NOT NULL REFERENCES payment_methods(code),
 amount numeric(14,2) NOT NULL CHECK (amount >= 0),
 PRIMARY KEY (document_id, line_no),
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id)
);

CREATE TABLE state_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 previous_state varchar(40),
 new_state varchar(40) NOT NULL,
 reason text,
 event_time timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id)
);
CREATE INDEX idx_state_events_document ON state_events (document_id, event_time);

-- Una clave de idempotencia por tenant. document_id se completa en la misma transacción que crea
-- el documento, así que una fila confirmada siempre lo tiene.
CREATE TABLE idempotency_records (
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 idempotency_key varchar(100) NOT NULL,
 request_hash varchar(64) NOT NULL,
 document_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id, idempotency_key)
);

-- Eventos por publicar a la mensajería. El payload solo lleva identificadores.
CREATE TABLE outbox_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 aggregate_id uuid NOT NULL,
 event_type varchar(60) NOT NULL,
 payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 dispatched_at timestamptz
);
CREATE INDEX idx_outbox_events_pending ON outbox_events (created_at) WHERE dispatched_at IS NULL;
