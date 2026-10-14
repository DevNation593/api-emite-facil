-- Paso a producción por emisor. Un emisor nace en el ambiente de pruebas y solo pasa a producción
-- cuando su propietario lo activa con la evidencia exigida (docs/15-produccion.md).

ALTER TABLE taxpayers ADD COLUMN production_enabled_at timestamptz;
ALTER TABLE taxpayers ADD COLUMN production_enabled_by uuid REFERENCES users(id);

-- Antes de existir la activación nadie pudo llegar a producción legítimamente.
UPDATE taxpayers SET default_environment = '1' WHERE default_environment = '2';

-- La base lo impone: ningún camino de la aplicación, ni una corrección manual, deja a un emisor en
-- producción sin su activación.
ALTER TABLE taxpayers ADD CONSTRAINT taxpayers_production_needs_activation
 CHECK (default_environment = '1' OR production_enabled_at IS NOT NULL);
