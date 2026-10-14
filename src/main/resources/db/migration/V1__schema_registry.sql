-- Esquemas XSD empacados en la aplicación. Los hashes son los de docs/xsd-source-registry.csv.
-- valid_from/valid_to quedan nulos hasta fijar vigencias con la ficha técnica (tarea N2 del plan maestro).
CREATE TABLE schema_registry (
 document_type char(2) NOT NULL,
 schema_version varchar(12) NOT NULL,
 resource_path text NOT NULL,
 sha256 char(64) NOT NULL,
 valid_from date,
 valid_to date,
 PRIMARY KEY (document_type, schema_version)
);
INSERT INTO schema_registry (document_type, schema_version, resource_path, sha256) VALUES
 ('01','1.0.0','xsd/invoice/1.0.0/factura_V1.0.0.xsd','0c2d62f15c9ed03f5e5d3559dc556dd254396396247f3ea919e63473b4299442'),
 ('01','1.1.0','xsd/invoice/1.1.0/factura_V1.1.0.xsd','62db9bf0ecceb00ef2b7ed136e59224815e5e5e33c77efc6a0552001e052eb8b'),
 ('01','2.0.0','xsd/invoice/2.0.0/factura_V2.0.0.xsd','cf1bbd68f43d594f51f9983e8ca2d756e7dfc745ae63ec8ea6301c22d05a1479'),
 ('01','2.1.0','xsd/invoice/2.1.0/factura_V2.1.0.xsd','5f2c37bc1a58bb40e8bbbc366cabe05d5dc199598aeea1561137370f8bd4eace');
