package com.example.einvoice.storage;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Recalcula la huella de los artefactos guardados y la compara con la que se anotó al guardarlos.
 * Los artefactos son de solo inserción, pero quien sea dueño de la tabla puede retirar esa defensa:
 * este recorrido es lo que lo delataría. Cada pasada toma primero lo nunca verificado y después lo
 * que lleva más tiempo sin verificarse, y deja medido cuánto sigue esperando su primera
 * verificación: si las pasadas no dan abasto, se ve.
 */
@Component
public class ArtifactIntegrityVerifier {

	private static final Logger log = LoggerFactory.getLogger(ArtifactIntegrityVerifier.class);

	public record Result(int checked, int corrupt) {
	}

	private record Verdict(UUID artifactId, boolean ok) {
	}

	private record Corrupt(UUID id, UUID documentId, String kind) {
	}

	private record Backlog(long total, double oldest) {
	}

	private final JdbcClient jdbc;

	private volatile Backlog backlog = new Backlog(0, 0);

	public ArtifactIntegrityVerifier(JdbcClient jdbc, MeterRegistry registry) {
		this.jdbc = jdbc;
		Gauge.builder("einvoice.artifacts.unverified", this, verifier -> verifier.backlog.total())
			.description("Artefactos que aún no se han verificado nunca, según la última pasada")
			.register(registry);
		Gauge.builder("einvoice.artifacts.unverified.oldest.seconds", this, verifier -> verifier.backlog.oldest())
			.description("Antigüedad del artefacto más antiguo que aún no se ha verificado nunca")
			.register(registry);
	}

	/**
	 * Verifica hasta {@code limit} artefactos. Primero se eligen (sin leer su contenido) y solo de
	 * los elegidos se calcula la huella, en la base: el contenido no viaja a la aplicación.
	 */
	@Transactional
	public Result verifyNext(int limit) {
		List<Verdict> verdicts = jdbc.sql("""
				WITH pending AS (
				 SELECT a.id, 0 AS turn, a.created_at AS since
				   FROM document_artifacts a
				  WHERE NOT EXISTS (SELECT 1 FROM artifact_integrity i WHERE i.artifact_id = a.id)
				  ORDER BY a.created_at, a.id LIMIT :limit),
				stale AS (
				 SELECT i.artifact_id AS id, 1 AS turn, i.verified_at AS since
				   FROM artifact_integrity i
				  ORDER BY i.verified_at, i.artifact_id LIMIT :limit),
				batch AS (
				 SELECT id FROM (SELECT * FROM pending UNION ALL SELECT * FROM stale) candidates
				  ORDER BY turn, since, id LIMIT :limit),
				checked AS (
				 SELECT a.id, encode(sha256(a.content), 'hex') = a.sha256 AS ok
				   FROM document_artifacts a JOIN batch b ON b.id = a.id)
				INSERT INTO artifact_integrity (artifact_id, verified_at, ok)
				SELECT id, clock_timestamp(), ok FROM checked
				ON CONFLICT (artifact_id) DO UPDATE SET verified_at = excluded.verified_at, ok = excluded.ok
				RETURNING artifact_id, ok
				""").param("limit", limit).query(Verdict.class).list();
		List<UUID> corrupt = verdicts.stream().filter(verdict -> !verdict.ok()).map(Verdict::artifactId).toList();
		if (!corrupt.isEmpty()) {
			jdbc.sql("SELECT id, document_id, kind FROM document_artifacts WHERE id IN (:ids)")
				.param("ids", corrupt)
				.query(Corrupt.class)
				.list()
				// Solo identificadores: el contenido de un comprobante no va al log.
				.forEach(artifact -> log.error(
						"Artefacto alterado: su contenido ya no corresponde a su huella. artefacto={} documento={} tipo={}",
						artifact.id(), artifact.documentId(), artifact.kind()));
		}
		backlog = jdbc.sql("""
				SELECT count(*) AS total, coalesce(extract(epoch FROM now() - min(a.created_at)), 0) AS oldest
				  FROM document_artifacts a
				 WHERE NOT EXISTS (SELECT 1 FROM artifact_integrity i WHERE i.artifact_id = a.id)
				""").query(Backlog.class).single();
		return new Result(verdicts.size(), corrupt.size());
	}

}
