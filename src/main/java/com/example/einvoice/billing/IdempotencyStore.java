package com.example.einvoice.billing;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.web.ApiException;

/**
 * Registro de claves de idempotencia por tenant. La unicidad la garantiza la clave primaria: un
 * segundo intento simultáneo espera a que el primero confirme o revierta, y entonces o encuentra el
 * documento ya creado o toma él la clave.
 */
@Component
public class IdempotencyStore {

	private record Existing(String requestHash, UUID documentId) {
	}

	private final JdbcClient jdbc;

	public IdempotencyStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Toma la clave para la transacción en curso. Devuelve vacío si es un intento nuevo, o el
	 * documento del intento anterior si la clave ya se usó con la misma petición.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<UUID> begin(UUID tenantId, String key, String requestHash) {
		int inserted = jdbc.sql("""
				INSERT INTO idempotency_records (tenant_id, idempotency_key, request_hash)
				VALUES (:tenant, :key, :hash)
				ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
				""").param("tenant", tenantId).param("key", key).param("hash", requestHash).update();
		if (inserted == 1) {
			return Optional.empty();
		}
		Existing existing = jdbc.sql("""
				SELECT request_hash, document_id FROM idempotency_records
				WHERE tenant_id = :tenant AND idempotency_key = :key
				""").param("tenant", tenantId).param("key", key).query(Existing.class).single();
		if (!existing.requestHash().equals(requestHash) || existing.documentId() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
					"La clave de idempotencia ya se usó con una petición distinta");
		}
		return Optional.of(existing.documentId());
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void complete(UUID tenantId, String key, UUID documentId) {
		jdbc.sql("""
				UPDATE idempotency_records SET document_id = :document
				WHERE tenant_id = :tenant AND idempotency_key = :key
				""").param("document", documentId).param("tenant", tenantId).param("key", key).update();
	}

}
