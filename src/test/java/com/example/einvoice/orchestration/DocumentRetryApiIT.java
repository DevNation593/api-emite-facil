package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestCertificates;

/** Reintento manual de un comprobante que quedó a medio camino. */
class DocumentRetryApiIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

	BillingScenario s;

	UUID document;

	@BeforeEach
	void invoiceThatCouldNotBeSigned() throws Exception {
		s = newBillingScenario();
		document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SIGNATURE_FAILED");
	}

	long retryEvents() {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?"
				+ " AND event_type = 'DOCUMENT_RETRY_REQUESTED' AND dispatched_at IS NULL", Long.class, document);
	}

	@Test
	void retrySchedulesTheDocumentAndLeavesAnEvent() throws Exception {
		jdbc.update("UPDATE fiscal_documents SET attempt_count = 3 WHERE id = ?", document);

		mvc.perform(as(post("/api/v1/documents/" + document + "/retry"), s.owner()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.id").value(document.toString()))
			.andExpect(jsonPath("$.state").value("SIGNATURE_FAILED"));

		assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM fiscal_documents WHERE id = ?",
				OffsetDateTime.class, document))
			.isBeforeOrEqualTo(OffsetDateTime.now());
		assertThat(jdbc.queryForObject("SELECT attempt_count FROM fiscal_documents WHERE id = ?", Integer.class,
				document))
			.isZero();
		assertThat(retryEvents()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ?"
				+ " AND action = 'DOCUMENT_RETRY_REQUESTED' AND tenant_id = ?", Long.class, document, s.tenant()))
			.isEqualTo(1);
	}

	@Test
	void retryAfterUploadingTheCertificateGetsItAuthorized() throws Exception {
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		mvc.perform(as(post("/api/v1/documents/" + document + "/retry"), s.owner())).andExpect(status().isAccepted());

		// Lo que hará el worker al recibir el evento.
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");
	}

	@Test
	void retryThatFixesNothingDoesNotStayScheduled() throws Exception {
		mvc.perform(as(post("/api/v1/documents/" + document + "/retry"), s.owner())).andExpect(status().isAccepted());

		// Sigue sin certificado: el worker lo intenta y vuelve a quedar sin firmar.
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SIGNATURE_FAILED");

		assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM fiscal_documents WHERE id = ?",
				OffsetDateTime.class, document))
			.isNull();
	}

	@Test
	void billerCanRetry() throws Exception {
		mvc.perform(as(post("/api/v1/documents/" + document + "/retry"), memberToken(s.tenant(), "BILLER")))
			.andExpect(status().isAccepted());
	}

	@Test
	void terminalDocumentIsNotRetryable() throws Exception {
		for (String state : new String[] { "AUTHORIZED", "RETURNED", "NOT_AUTHORIZED", "XSD_INVALID" }) {
			jdbc.update("UPDATE fiscal_documents SET state = ? WHERE id = ?", state, document);

			mvc.perform(as(post("/api/v1/documents/" + document + "/retry"), s.owner()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_RETRYABLE"));
		}
		assertThat(retryEvents()).isZero();
		assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM fiscal_documents WHERE id = ?",
				OffsetDateTime.class, document))
			.isNull();
	}

	@Test
	void auditorCannotRetry() throws Exception {
		mvc.perform(as(post("/api/v1/documents/" + document + "/retry"), memberToken(s.tenant(), "AUDITOR")))
			.andExpect(status().isForbidden());
		assertThat(retryEvents()).isZero();
	}

	@Test
	void foreignDocumentIsNotFound() throws Exception {
		BillingScenario other = newBillingScenario();

		mvc.perform(as(post("/api/v1/documents/" + document + "/retry"), other.owner()))
			.andExpect(status().isNotFound());
		assertThat(retryEvents()).isZero();
	}

	@Test
	void exhaustedDocumentIsRevived() throws Exception {
		jdbc.update("UPDATE fiscal_documents SET state = 'RECEIVED', attempt_count = 5, next_attempt_at = NULL"
				+ " WHERE id = ?", document);

		mvc.perform(as(post("/api/v1/documents/" + document + "/retry"), s.owner()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.state").value("RECEIVED"));

		assertThat(jdbc.queryForObject(
				"SELECT attempt_count || '|' || (next_attempt_at IS NOT NULL) FROM fiscal_documents WHERE id = ?",
				String.class, document))
			.isEqualTo("0|true");
	}

}
