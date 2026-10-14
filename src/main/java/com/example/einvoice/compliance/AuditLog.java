package com.example.einvoice.compliance;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Bitácora de auditoría. Se escribe dentro de la transacción del cambio que registra: si el cambio
 * no se confirma, el evento tampoco.
 */
@Component
public class AuditLog {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public AuditLog(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(UUID tenantId, UUID actorId, String action, String entityType, UUID entityId,
			Map<String, Object> metadata) {
		jdbc.sql("""
				INSERT INTO audit_events (tenant_id, actor_id, action, entity_type, entity_id, metadata)
				VALUES (:tenantId, :actorId, :action, :entityType, :entityId, CAST(:metadata AS jsonb))
				""")
			.param("tenantId", tenantId)
			.param("actorId", actorId)
			.param("action", action)
			.param("entityType", entityType)
			.param("entityId", entityId)
			.param("metadata", json.writeValueAsString(metadata))
			.update();
	}

}
