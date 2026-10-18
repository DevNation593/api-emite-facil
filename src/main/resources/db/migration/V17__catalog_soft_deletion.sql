ALTER TABLE customers ADD COLUMN deleted_at timestamptz;
ALTER TABLE products ADD COLUMN deleted_at timestamptz;
ALTER TABLE customers ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN version bigint NOT NULL DEFAULT 0;

ALTER TABLE customers DROP CONSTRAINT customers_identification_key;
CREATE UNIQUE INDEX customers_identification_key
 ON customers (tenant_id, id_type, id_number) WHERE deleted_at IS NULL;

ALTER TABLE products DROP CONSTRAINT products_sku_key;
CREATE UNIQUE INDEX products_sku_key
 ON products (tenant_id, sku) WHERE deleted_at IS NULL;
