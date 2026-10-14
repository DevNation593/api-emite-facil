package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.support.SmtpStub.Reply;

/** Las entregas con un intento vencido vuelven a la cola de entrega a través del outbox. */
class DueDeliverySchedulerIT extends AbstractDeliveryIT {

	@Autowired
	DueDeliveryScheduler scheduler;

	long dueEvents() {
		return jdbc.queryForObject(
				"SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'DELIVERY_DUE'", Long.class,
				document);
	}

	@Test
	void dueDeliveryGetsAnEventAndALease() {
		smtp.onRecipient(email, Reply.failData(451, "4.3.0 Intente más tarde"));
		deliveries.process(s.tenant(), document);
		jdbc.update("UPDATE deliveries SET next_attempt_at = now() - interval '1 second' WHERE document_id = ?",
				document);

		assertThat(scheduler.enqueueDue()).isGreaterThanOrEqualTo(1);

		assertThat(dueEvents()).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT tenant_id::text || '|' || (dispatched_at IS NULL) FROM outbox_events"
						+ " WHERE aggregate_id = ? AND event_type = 'DELIVERY_DUE'",
				String.class, document))
			.isEqualTo(s.tenant() + "|true");
		// Si el mensaje se perdiera, la entrega volvería a salir al vencer este plazo (5 minutos).
		assertThat(nextDeliveryAttempt()).isBetween(OffsetDateTime.now().plusMinutes(4),
				OffsetDateTime.now().plusMinutes(6));
		// Sigue pendiente: el planificador solo avisa, no envía.
		assertThat(delivery()).containsEntry("status", "PENDING");

		scheduler.enqueueDue();
		assertThat(dueEvents()).isEqualTo(1);
	}

	@Test
	void sentFailedAndFutureDeliveriesAreIgnored() {
		// Enviada: nada programado.
		deliveries.process(s.tenant(), document);
		scheduler.enqueueDue();
		assertThat(dueEvents()).isZero();

		// Pendiente pero con el siguiente intento en el futuro.
		jdbc.update("UPDATE deliveries SET status = 'PENDING', next_attempt_at = now() + interval '1 hour'"
				+ " WHERE document_id = ?", document);
		scheduler.enqueueDue();
		assertThat(dueEvents()).isZero();

		// Fallida definitivamente: sin programar.
		jdbc.update("UPDATE deliveries SET status = 'FAILED', next_attempt_at = NULL WHERE document_id = ?", document);
		scheduler.enqueueDue();
		assertThat(dueEvents()).isZero();
	}

}
