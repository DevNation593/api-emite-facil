package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.support.WebhookReceiverStub.Reply;

/** Los avisos con un intento vencido vuelven a su cola a través del outbox. */
class DueWebhookSchedulerIT extends AbstractWebhookIT {

	@Autowired
	DueWebhookScheduler scheduler;

	long dueEvents(UUID document) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'WEBHOOK_DUE'", Long.class,
				document);
	}

	@Test
	void aDocumentWithDueNotificationsGetsOneEventAndALease() throws Exception {
		subscribe("document.received", "document.authorized");
		UUID document = invoiceIn("AUTHORIZED");
		assertThat(notifications(document)).hasSize(2);

		assertThat(scheduler.enqueueDue()).isGreaterThanOrEqualTo(1);

		// Dos avisos del mismo comprobante, un solo evento: el worker los envía juntos.
		assertThat(dueEvents(document)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT tenant_id::text || '|' || (dispatched_at IS NULL) || '|' || payload::text FROM outbox_events
				 WHERE aggregate_id = ? AND event_type = 'WEBHOOK_DUE'
				""", String.class, document)).startsWith(s.tenant() + "|true|").doesNotContain("ficha", "whsec_");
		// Si el mensaje se perdiera, el aviso volvería a salir al vencer este plazo (5 minutos).
		assertThat(jdbc.queryForObject("""
				SELECT bool_and(next_attempt_at BETWEEN now() + interval '4 minutes' AND now() + interval '6 minutes'
				            AND status = 'PENDING') FROM webhook_deliveries WHERE document_id = ?
				""", Boolean.class, document)).isTrue();
		scheduler.enqueueDue();
		assertThat(dueEvents(document)).isEqualTo(1);
	}

	@Test
	void sentFailedWaitingAndFreshlyTakenNotificationsAreLeftAlone() throws Exception {
		var subscribed = subscribe("document.authorized");
		UUID sent = invoiceIn("AUTHORIZED");
		webhooks.process(s.tenant(), sent);
		receiver.on(subscribed.name(), Reply.status(500));
		UUID waiting = invoiceIn("AUTHORIZED");
		webhooks.process(s.tenant(), waiting);
		UUID failed = invoiceIn("AUTHORIZED");
		jdbc.update("UPDATE webhook_deliveries SET attempt_count = 99 WHERE document_id = ?", failed);
		webhooks.process(s.tenant(), failed);
		// Y uno que otro proceso acaba de reservar: su plazo de envío no ha vencido.
		receiver.on(subscribed.name(), Reply.status(200));
		UUID taken = invoiceIn("AUTHORIZED");
		jdbc.update("""
				UPDATE webhook_deliveries SET status = 'SENDING', sending_since = now(), attempt_count = 1,
				       next_attempt_at = now() + interval '2 minutes' WHERE document_id = ?
				""", taken);

		scheduler.enqueueDue();

		assertThat(dueEvents(sent)).isZero();
		assertThat(dueEvents(waiting)).isZero();
		assertThat(dueEvents(failed)).isZero();
		assertThat(dueEvents(taken)).isZero();
		// Cuando pasa la espera del reintento, sí.
		jdbc.update("UPDATE webhook_deliveries SET retry_after = now(), next_attempt_at = now() WHERE document_id = ?", waiting);
		scheduler.enqueueDue();
		assertThat(dueEvents(waiting)).isEqualTo(1);
	}

	@Test
	void anAbandonedSendComesBackWhenItsLeaseExpires() throws Exception {
		subscribe("document.authorized");
		UUID document = invoiceIn("AUTHORIZED");
		// Un proceso lo reservó y murió antes de anotar el resultado.
		assertThat(jdbc.update("""
				UPDATE webhook_deliveries SET status = 'SENDING', sending_since = now() - interval '3 minutes',
				       attempt_count = 1, next_attempt_at = now() - interval '1 minute' WHERE document_id = ?
				""", document)).isEqualTo(1);

		scheduler.enqueueDue();

		assertThat(dueEvents(document)).isEqualTo(1);
		assertThat(webhooks.process(s.tenant(), document)).isEqualTo(1);
	}

}
