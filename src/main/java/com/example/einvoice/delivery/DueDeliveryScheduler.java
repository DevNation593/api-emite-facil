package com.example.einvoice.delivery;

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
 * Devuelve a la cola de entrega los comprobantes con una entrega cuyo siguiente intento ya venció:
 * reintentos de correo y envíos que se quedaron sin resultado.
 */
@Component
public class DueDeliveryScheduler {

	public static final String DELIVERY_DUE = "DELIVERY_DUE";

	private static final int BATCH = 100;

	private record Due(UUID tenantId, UUID documentId) {
	}

	private final JdbcClient jdbc;

	private final OutboxWriter outbox;

	private final Duration redeliveryLease;

	public DueDeliveryScheduler(JdbcClient jdbc, OutboxWriter outbox,
			@Value("${einvoice.worker.redelivery-lease:5m}") Duration redeliveryLease) {
		this.jdbc = jdbc;
		this.outbox = outbox;
		this.redeliveryLease = redeliveryLease;
	}

	/** Deja un evento por cada comprobante con entregas vencidas y devuelve cuántos fueron. */
	@Transactional
	public int enqueueDue() {
		// El plazo nuevo es una red de seguridad: si el mensaje se pierde, la entrega vuelve a salir.
		List<Due> due = jdbc.sql("""
				UPDATE deliveries SET next_attempt_at = now() + make_interval(secs => CAST(:lease AS double precision))
				WHERE id IN (SELECT id FROM deliveries WHERE next_attempt_at <= now()
				 ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED)
				RETURNING tenant_id, document_id
				""").param("lease", redeliveryLease.toMillis() / 1000.0).param("batch", BATCH).query(Due.class).list();
		Set<Due> documents = new LinkedHashSet<>(due);
		for (Due document : documents) {
			outbox.write(document.tenantId(), document.documentId(), DELIVERY_DUE,
					Map.of("documentId", document.documentId().toString()));
		}
		return documents.size();
	}

}
