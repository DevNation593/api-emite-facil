-- Datos fiscales del emisor que el XML lleva como leyendas, su copia congelada en cada documento,
-- artefactos XML y certificados de firma (docs/07-xades-signature.md, docs/14-xsd-integration.md).

-- Ficha técnica v2.34: obligadoContabilidad y contribuyenteEspecial (Anexo 3), agenteRetencion
-- (Anexo 21: número de resolución sin ceros a la izquierda) y contribuyenteRimpe (Anexo 22).
ALTER TABLE taxpayers
 ADD COLUMN accounting_required boolean NOT NULL DEFAULT false,
 ADD COLUMN special_taxpayer_code varchar(13) CHECK (special_taxpayer_code ~ '^[A-Za-z0-9]{3,13}$'),
 ADD COLUMN withholding_agent_code varchar(8) CHECK (withholding_agent_code ~ '^[1-9][0-9]{0,7}$'),
 ADD COLUMN rimpe_regime varchar(20) NOT NULL DEFAULT 'NONE'
  CHECK (rimpe_regime IN ('NONE','EMPRENDEDOR','NEGOCIO_POPULAR'));

-- El XSD del SRI solo admite como RUC del emisor diez dígitos seguidos de 001. Si ya existiera un
-- emisor que no lo cumple, la migración falla aquí a propósito: sus facturas nunca serían válidas y
-- hay que corregirlo antes de continuar.
ALTER TABLE taxpayers
 ADD CONSTRAINT taxpayers_ruc_main_establishment CHECK (ruc ~ '^[0-9]{10}001$');

-- El documento congela lo que el XML necesita del emisor: un cambio posterior en el emisor o en el
-- establecimiento no altera un comprobante ya emitido. provider_ruc es el RUC del proveedor del
-- sistema de facturación (Anexo 26), nulo si no está configurado.
ALTER TABLE fiscal_documents
 ADD COLUMN issuer_legal_name text,
 ADD COLUMN issuer_trade_name text,
 ADD COLUMN issuer_head_office_address text,
 ADD COLUMN establishment_address text,
 ADD COLUMN issuer_accounting_required boolean,
 ADD COLUMN issuer_special_taxpayer_code varchar(13),
 ADD COLUMN issuer_withholding_agent_code varchar(8),
 ADD COLUMN issuer_rimpe_regime varchar(20),
 ADD COLUMN provider_ruc varchar(13) CHECK (provider_ruc ~ '^[0-9]{13}$');

UPDATE fiscal_documents d
SET issuer_legal_name = t.legal_name,
    issuer_trade_name = t.trade_name,
    issuer_head_office_address = t.head_office_address,
    issuer_accounting_required = t.accounting_required,
    issuer_special_taxpayer_code = t.special_taxpayer_code,
    issuer_withholding_agent_code = t.withholding_agent_code,
    issuer_rimpe_regime = t.rimpe_regime
FROM taxpayers t
WHERE t.id = d.taxpayer_id;

UPDATE fiscal_documents d
SET establishment_address = e.address
FROM emission_points ep JOIN establishments e ON e.id = ep.establishment_id
WHERE ep.id = d.emission_point_id;

ALTER TABLE fiscal_documents
 ALTER COLUMN issuer_legal_name SET NOT NULL,
 ALTER COLUMN issuer_head_office_address SET NOT NULL,
 ALTER COLUMN establishment_address SET NOT NULL,
 ALTER COLUMN issuer_accounting_required SET NOT NULL,
 ALTER COLUMN issuer_rimpe_regime SET NOT NULL;

-- Artefactos inmutables de un comprobante (XML sin firmar y firmado) con su SHA-256. El contenido
-- vive aquí mientras no se decida el almacenamiento de objetos.
CREATE TABLE document_artifacts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 document_id uuid NOT NULL,
 kind varchar(30) NOT NULL CHECK (kind IN ('XML_UNSIGNED','XML_SIGNED')),
 sha256 varchar(64) NOT NULL,
 content bytea NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id),
 UNIQUE (document_id, kind)
);

-- Certificados de firma. El archivo PKCS#12 y su contraseña se guardan cifrados (cifrado de sobre
-- AES-256-GCM); el resto son metadatos públicos del certificado. Un solo certificado activo por emisor.
CREATE TABLE certificates (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 taxpayer_id uuid NOT NULL,
 subject text NOT NULL,
 issuer text NOT NULL,
 serial_number text NOT NULL,
 fingerprint_sha256 varchar(64) NOT NULL,
 valid_from timestamptz NOT NULL,
 valid_to timestamptz NOT NULL,
 status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','REPLACED')),
 encrypted_p12 bytea NOT NULL,
 encrypted_password bytea NOT NULL,
 created_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (tenant_id, taxpayer_id) REFERENCES taxpayers (tenant_id, id)
);
CREATE UNIQUE INDEX uq_certificates_active ON certificates (taxpayer_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_certificates_taxpayer ON certificates (tenant_id, taxpayer_id, created_at DESC);
