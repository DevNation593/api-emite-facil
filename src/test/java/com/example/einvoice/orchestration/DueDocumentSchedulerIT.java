package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;

/** Los documentos con un intento vencido vuelven a la cola a través del outbox. */
class DueDocumentSchedulerIT extends AbstractIntegrationTest {

	@Autowired
	DueDocumentScheduler scheduler;

	long dueEvents(UUID document) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'DOCUMENT_DUE'", Long.class,
				document);
	}

	OffsetDateTime nextAttemptAt(UUID document) {
		return jdbc.queryForObject("SELECT next_attempt_at FROM fiscal_documents WHERE id = ?", OffsetDateTime.class,
				document);
	}

	@Test
	void dueDocumentGetsAnEventAndALease() throws Exception {
		BillingScenario s = newBillingScenario();
		UUID document = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		jdbc.update("UPDATE fiscal_documents SET next_attempt_at = now() - interval '1 second' WHERE id = ?", document);

		assertThat(scheduler.enqueueDue()).isGreaterThanOrEqualTo(1);

		assertThat(dueEvents(document)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT tenant_id::text || '|' || (dispatched_at IS NULL) FROM outbox_events"
						+ " WHERE aggregate_id = ? AND event_type = 'DOCUMENT_DUE'",
				String.class, document))
			.isEqualTo(s.tenant() + "|true");
		// Si el mensaje se perdiera, el documento volvería a salir al vencer este plazo (5 minutos).
		assertThat(nextAttemptAt(document)).isBetween(OffsetDateTime.now().plusMinutes(4),
				OffsetDateTime.now().plusMinutes(6));

		scheduler.enqueueDue();
		assertThat(dueEvents(document)).isEqualTo(1);
	}

	@Test
	void freshInvoiceIsScheduledAsASafetyNet() throws Exception {
		BillingScenario s = newBillingScenario();

		UUID document = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));

		// Si el evento de emisión se pierde o acaba en la cola de mensajes muertos, el planificador la retoma.
		assertThat(nextAttemptAt(document)).isBetween(OffsetDateTime.now().plusMinutes(4),
				OffsetDateTime.now().plusMinutes(6));
		assertThat(jdbc.queryForObject("SELECT attempt_count FROM fiscal_documents WHERE id = ?", Integer.class,
				document))
			.isZero();
	}

	@Test
	void futureAndUnscheduledDocumentsAreIgnored() throws Exception {
		BillingScenario s = newBillingScenario();
		UUID later = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		UUID unscheduled = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		jdbc.update("UPDATE fiscal_documents SET next_attempt_at = now() + interval '1 hour' WHERE id = ?", later);
		jdbc.update("UPDATE fiscal_documents SET next_attempt_at = NULL WHERE id = ?", unscheduled);

		scheduler.enqueueDue();

		assertThat(dueEvents(later)).isZero();
		assertThat(dueEvents(unscheduled)).isZero();
		assertThat(nextAttemptAt(unscheduled)).isNull();
	}

}
