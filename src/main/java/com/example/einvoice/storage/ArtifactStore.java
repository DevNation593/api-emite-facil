package com.example.einvoice.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Artefactos inmutables de un comprobante, con su SHA-256. Se insertan una vez y no se actualizan.
 * El contenido vive en PostgreSQL mientras no se decida el almacenamiento de objetos
 * (docs/13-deployment.md); los servicios solo dependen de esta clase.
 */
@Component
public class ArtifactStore {

	public record StoredArtifact(byte[] content, String sha256) {
	}

	private final JdbcClient jdbc;

	public ArtifactStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Guarda el artefacto y devuelve su SHA-256. Falla si el documento ya tiene uno de ese tipo. */
	@Transactional(propagation = Propagation.MANDATORY)
	public String save(UUID tenantId, UUID documentId, ArtifactKind kind, byte[] content) {
		String sha256 = sha256(content);
		jdbc.sql("""
				INSERT INTO document_artifacts (tenant_id, document_id, kind, sha256, content)
				VALUES (:tenantId, :documentId, :kind, :sha256, :content)
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("kind", kind.name())
			.param("sha256", sha256)
			.param("content", content)
			.update();
		return sha256;
	}

	public Optional<StoredArtifact> find(UUID tenantId, UUID documentId, ArtifactKind kind) {
		return jdbc.sql("""
				SELECT content, sha256 FROM document_artifacts
				WHERE tenant_id = :tenantId AND document_id = :documentId AND kind = :kind
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("kind", kind.name())
			.query(StoredArtifact.class)
			.optional();
	}

	/** Artefacto con el número del comprobante al que pertenece, para nombrar la descarga. */
	public record Download(byte[] content, String sha256, String documentType, String number) {
	}

	public Optional<Download> findForDownload(UUID tenantId, UUID documentId, ArtifactKind kind) {
		return jdbc.sql("""
				SELECT a.content, a.sha256, d.document_type,
				       d.establishment_code || '-' || d.emission_point_code || '-' || lpad(d.sequence::text, 9, '0') AS number
				  FROM document_artifacts a JOIN fiscal_documents d ON d.tenant_id = a.tenant_id AND d.id = a.document_id
				 WHERE a.tenant_id = :tenantId AND a.document_id = :documentId AND a.kind = :kind
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("kind", kind.name())
			.query(Download.class)
			.optional();
	}

	public boolean exists(UUID tenantId, UUID documentId, ArtifactKind kind) {
		return jdbc.sql("""
				SELECT count(*) FROM document_artifacts
				WHERE tenant_id = :tenantId AND document_id = :documentId AND kind = :kind
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("kind", kind.name())
			.query(Long.class)
			.single() > 0;
	}

	public static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
