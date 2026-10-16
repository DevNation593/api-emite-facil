package com.example.einvoice.apikey;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Gestión de las API keys de una empresa: crear, listar y revocar (solo OWNER y ADMIN), y comprobar
 * la key que presenta un sistema externo. El valor completo de una key sale una vez, en la respuesta
 * de creación; ni él ni su hash llegan a la auditoría ni a los registros.
 */
@Service
public class ApiKeyService {

	/** Máximo de keys activas por empresa. */
	static final int MAX_ACTIVE = 10;

	private static final String ACTIVE_NAME_INDEX = "uq_api_keys_active_name";

	private final JdbcClient jdbc;

	private final AuditLog audit;

	public ApiKeyService(JdbcClient jdbc, AuditLog audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	@Transactional
	public CreatedApiKey create(TenantAccess access, String name) {
		access.requireWrite();
		String trimmed = name.strip();
		// Serializa las altas de la empresa para que el tope se decida de una en una.
		jdbc.sql("SELECT id FROM tenants WHERE id = :tenant FOR UPDATE")
			.param("tenant", access.tenantId())
			.query()
			.listOfRows();
		long active = jdbc.sql("SELECT count(*) FROM api_keys WHERE tenant_id = :tenant AND revoked_at IS NULL")
			.param("tenant", access.tenantId())
			.query(Long.class)
			.single();
		if (active >= MAX_ACTIVE) {
			throw new ApiException(HttpStatus.CONFLICT, "API_KEY_LIMIT",
					"La empresa ya tiene el máximo de API keys activas; revoque alguna");
		}
		ApiKeyCredential.Generated generated = ApiKeyCredential.generate();
		UUID id = UUID.randomUUID();
		OffsetDateTime createdAt;
		try {
			createdAt = jdbc.sql("""
					INSERT INTO api_keys (id, tenant_id, created_by, name, public_id, secret_hash)
					VALUES (:id, :tenant, :user, :name, :publicId, :hash)
					RETURNING created_at
					""")
				.param("id", id)
				.param("tenant", access.tenantId())
				.param("user", access.userId())
				.param("name", trimmed)
				.param("publicId", generated.publicId())
				.param("hash", generated.secretHash())
				.query(OffsetDateTime.class)
				.single();
		}
		catch (DuplicateKeyException e) {
			// La transacción ya está abortada: no se puede consultar nada más, solo responder.
			if (e.getMessage() != null && e.getMessage().contains(ACTIVE_NAME_INDEX)) {
				throw new ApiException(HttpStatus.CONFLICT, "API_KEY_NAME_TAKEN",
						"Ya hay una API key activa con ese nombre");
			}
			throw e;
		}
		audit.record(access, "API_KEY_CREATED", "API_KEY", id, Map.of("name", trimmed));
		return new CreatedApiKey(id, trimmed, generated.publicId(), generated.value(), createdAt);
	}

	@Transactional(readOnly = true)
	public List<ApiKeyView> list(TenantAccess access) {
		access.requireWrite();
		return jdbc.sql("""
				SELECT k.id, k.name, k.public_id, u.email AS created_by, k.created_at, k.last_used_at, k.revoked_at
				  FROM api_keys k JOIN users u ON u.id = k.created_by
				 WHERE k.tenant_id = :tenant
				 ORDER BY (k.revoked_at IS NOT NULL), k.created_at DESC, k.id
				""")
			.param("tenant", access.tenantId())
			.query(ApiKeyView.class)
			.list();
	}

	private record StoredKey(UUID id, UUID tenantId, UUID createdBy, String secretHash) {
	}

	/**
	 * Comprueba el valor de la cabecera X-Api-Key. Vacío si el formato no vale (sin consultar la base),
	 * si la key no existe, si el secreto no coincide o si está revocada; quien llama no distingue entre
	 * esos casos. Sin caché: una key revocada deja de servir en la petición siguiente.
	 */
	@Transactional(readOnly = true)
	public Optional<ApiKeyAuthentication> authenticate(String headerValue) {
		Optional<ApiKeyCredential.Presented> presented = ApiKeyCredential.parse(headerValue);
		if (presented.isEmpty()) {
			return Optional.empty();
		}
		String secret = presented.get().secret();
		return jdbc.sql("""
				SELECT id, tenant_id, created_by, secret_hash FROM api_keys
				 WHERE public_id = :publicId AND revoked_at IS NULL
				""")
			.param("publicId", presented.get().publicId())
			.query(StoredKey.class)
			.optional()
			.filter(stored -> ApiKeyCredential.matches(secret, stored.secretHash()))
			.map(stored -> new ApiKeyAuthentication(stored.id(), stored.tenantId(), stored.createdBy()));
	}

	/** Anota el último uso, como mucho una escritura por minuto y key. */
	@Transactional
	public void touch(UUID apiKeyId) {
		jdbc.sql("""
				UPDATE api_keys SET last_used_at = now()
				 WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
				""")
			.param("id", apiKeyId)
			.update();
	}

	/** Revocar una key ya revocada no cambia nada ni se audita; una que no es de la empresa es 404. */
	@Transactional
	public void revoke(TenantAccess access, UUID id) {
		access.requireWrite();
		List<String> revoked = jdbc.sql("""
				UPDATE api_keys SET revoked_at = now(), revoked_by = :user
				 WHERE id = :id AND tenant_id = :tenant AND revoked_at IS NULL
				RETURNING name
				""")
			.param("user", access.userId())
			.param("id", id)
			.param("tenant", access.tenantId())
			.query(String.class)
			.list();
		if (revoked.isEmpty()) {
			boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM api_keys WHERE id = :id AND tenant_id = :tenant)")
				.param("id", id)
				.param("tenant", access.tenantId())
				.query(Boolean.class)
				.single();
			if (!exists) {
				throw new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "La API key no existe");
			}
			return;
		}
		audit.record(access, "API_KEY_REVOKED", "API_KEY", id,
				Map.of("name", revoked.get(0)));
	}

}
