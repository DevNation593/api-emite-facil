package com.example.einvoice.orchestration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.billing.OutboxWriter;

/**
 * Devuelve a la cola los comprobantes cuyo siguiente intento ya venció: reintentos ante el SRI,
 * consultas de autorización pendientes y envíos que se quedaron sin resultado.
 */
@Component
public class DueDocumentScheduler {

	public static final String DOCUMENT_DUE = "DOCUMENT_DUE";

	private static final int BATCH = 100;

	private record Due(UUID tenantId, UUID id) {
	}

	private final JdbcClient jdbc;

	private final OutboxWriter outbox;

	private final Duration redeliveryLease;

	public DueDocumentScheduler(JdbcClient jdbc, OutboxWriter outbox,
			@Value("${einvoice.worker.redelivery-lease:5m}") Duration redeliveryLease) {
		this.jdbc = jdbc;
		this.outbox = outbox;
		this.redeliveryLease = redeliveryLease;
	}

	/** Deja un evento por cada documento vencido y devuelve cuántos fueron. */
	@Transactional
	public int enqueueDue() {
		// El plazo nuevo es una red de seguridad: si el mensaje se pierde o el worker muere, el
		// documento vuelve a salir. Quien lo procese fijará el siguiente intento real.
		List<Due> due = jdbc.sql("""
				UPDATE fiscal_documents SET next_attempt_at = now() + make_interval(secs => CAST(:lease AS double precision))
				WHERE id IN (SELECT id FROM fiscal_documents WHERE next_attempt_at <= now()
				 ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED)
				RETURNING tenant_id, id
				""").param("lease", redeliveryLease.toMillis() / 1000.0).param("batch", BATCH).query(Due.class).list();
		for (Due document : due) {
			outbox.write(document.tenantId(), document.id(), DOCUMENT_DUE,
					Map.of("documentId", document.id().toString()));
		}
		return due.size();
	}

}
