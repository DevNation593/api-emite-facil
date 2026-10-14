package com.example.einvoice.billing;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Deja un evento por publicar en la misma transacción que el cambio de negocio. El payload lleva
 * solo identificadores: nunca XML, certificados ni datos del cliente.
 */
@Component
public class OutboxWriter {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void write(UUID tenantId, UUID aggregateId, String eventType, Map<String, String> identifiers) {
		jdbc.sql("""
				INSERT INTO outbox_events (tenant_id, aggregate_id, event_type, payload)
				VALUES (:tenant, :aggregate, :eventType, CAST(:payload AS jsonb))
				""")
			.param("tenant", tenantId)
			.param("aggregate", aggregateId)
			.param("eventType", eventType)
			.param("payload", json.writeValueAsString(identifiers))
			.update();
	}

}
