-- Resultado de la última verificación de cada artefacto (ArtifactIntegrityVerifier). Vive aparte
-- porque document_artifacts es de solo inserción.
CREATE TABLE artifact_integrity (
 -- Sin clave foránea a propósito: los artefactos no se pueden borrar, y con ella PostgreSQL
 -- rechazaría un TRUNCATE de document_artifacts por la referencia antes de que lo haga su disparador.
 artifact_id uuid PRIMARY KEY,
 verified_at timestamptz NOT NULL,
 ok boolean NOT NULL
);
CREATE INDEX idx_artifact_integrity_verified ON artifact_integrity (verified_at);
CREATE INDEX idx_artifact_integrity_corrupt ON artifact_integrity (artifact_id) WHERE NOT ok;
