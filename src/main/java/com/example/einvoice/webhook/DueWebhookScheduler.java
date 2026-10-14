package com.example.einvoice.webhook;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.billing.OutboxWriter;

/**
 * Devuelve a la cola de avisos los comprobantes con un aviso cuyo siguiente intento ya venció: los
 * recién anotados, los reintentos y los envíos que se quedaron sin resultado.
 */
@Component
public class DueWebhookScheduler {

	public static final String WEBHOOK_DUE = "WEBHOOK_DUE";

	/** Un aviso de prueba vencido: el agregado es la suscripción, porque no hay comprobante. */
	public static final String WEBHOOK_TEST = "WEBHOOK_TEST";

	private static final int BATCH = 100;

	private record Due(UUID tenantId, UUID documentId, UUID subscriptionId) {
	}

	/** Un evento por comprobante con avisos vencidos, y uno por suscripción con un aviso de prueba vencido. */
	private record Event(UUID tenantId, UUID aggregateId, String eventType) {

		static Event of(Due due) {
			return due.documentId() != null ? new Event(due.tenantId(), due.documentId(), WEBHOOK_DUE)
					: new Event(due.tenantId(), due.subscriptionId(), WEBHOOK_TEST);
		}

		Map<String, String> identifiers() {
			return Map.of(WEBHOOK_DUE.equals(eventType) ? "documentId" : "subscriptionId", aggregateId.toString());
		}

	}

	private final JdbcClient jdbc;

	private final OutboxWriter outbox;

	private final WebhookProperties properties;

	private final Duration redeliveryLease;

	public DueWebhookScheduler(JdbcClient jdbc, OutboxWriter outbox, WebhookProperties properties,
			@Value("${einvoice.worker.redelivery-lease:5m}") Duration redeliveryLease) {
		this.jdbc = jdbc;
		this.outbox = outbox;
		this.properties = properties;
		this.redeliveryLease = redeliveryLease;
	}

	/** Deja un evento por cada comprobante con avisos vencidos y devuelve cuántos fueron. */
	@Transactional
	public int enqueueDue() {
		if (!properties.enabled()) {
			return 0;
		}
		// El plazo nuevo es una red de seguridad: si el mensaje se pierde, el aviso vuelve a salir.
		List<Due> due = jdbc.sql("""
				UPDATE webhook_deliveries SET next_attempt_at = now() + make_interval(secs => CAST(:lease AS double precision))
				WHERE id IN (SELECT id FROM webhook_deliveries WHERE next_attempt_at <= now()
				 ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED)
				RETURNING tenant_id, document_id, subscription_id
				""").param("lease", redeliveryLease.toMillis() / 1000.0).param("batch", BATCH).query(Due.class).list();
		Set<Event> events = new LinkedHashSet<>();
		for (Due row : due) {
			events.add(Event.of(row));
		}
		for (Event event : events) {
			outbox.write(event.tenantId(), event.aggregateId(), event.eventType(), event.identifiers());
		}
		return events.size();
	}

}
