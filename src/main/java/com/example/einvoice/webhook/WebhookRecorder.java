package com.example.einvoice.webhook;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anota los avisos que hay que dar cuando a un comprobante le pasa algo, en la misma transacción que
 * lo cambia: si el cambio se guarda, el aviso queda pendiente; si no, no. Un aviso por cada
 * suscripción activa de la empresa que pidió ese tipo de evento, todos con el mismo identificador.
 */
@Component
public class WebhookRecorder {

	private final JdbcClient jdbc;

	private final WebhookProperties properties;

	public WebhookRecorder(JdbcClient jdbc, WebhookProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	/** El comprobante llegó a ese estado. Los estados que no se anuncian se ignoran. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void stateReached(UUID tenantId, UUID documentId, String state) {
		String eventType = switch (state) {
			case "RECEIVED" -> "document.received";
			case "AUTHORIZED" -> "document.authorized";
			case "RETURNED", "NOT_AUTHORIZED" -> "document.rejected";
			default -> null;
		};
		if (eventType != null) {
			record(tenantId, documentId, eventType, state);
		}
	}

	/** Salió un correo de entrega del comprobante (que para entonces está autorizado). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void delivered(UUID tenantId, UUID documentId) {
		record(tenantId, documentId, "document.delivered", "AUTHORIZED");
	}

	private void record(UUID tenantId, UUID documentId, String eventType, String state) {
		if (!properties.enabled()) {
			return;
		}
		jdbc.sql("""
				INSERT INTO webhook_deliveries (tenant_id, subscription_id, event_id, event_type, document_id,
				 document_state, next_attempt_at)
				SELECT s.tenant_id, s.id, :eventId, :eventType, :documentId, :state, now()
				  FROM webhook_subscriptions s
				 WHERE s.tenant_id = :tenantId AND s.status = 'ACTIVE' AND CAST(:eventType AS text) = ANY (s.event_types)
				""")
			.param("eventId", UUID.randomUUID())
			.param("eventType", eventType)
			.param("documentId", documentId)
			.param("state", state)
			.param("tenantId", tenantId)
			.update();
	}

}
