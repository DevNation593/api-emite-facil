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

	private static final int BATCH = 100;

	private record Due(UUID tenantId, UUID documentId) {
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
				RETURNING tenant_id, document_id
				""").param("lease", redeliveryLease.toMillis() / 1000.0).param("batch", BATCH).query(Due.class).list();
		Set<Due> documents = new LinkedHashSet<>(due);
		for (Due document : documents) {
			outbox.write(document.tenantId(), document.documentId(), WEBHOOK_DUE,
					Map.of("documentId", document.documentId().toString()));
		}
		return documents.size();
	}

}
