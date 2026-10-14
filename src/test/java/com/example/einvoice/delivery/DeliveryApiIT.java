package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.OutboxWriter;
import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.tenancy.Role;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/** Consulta y reenvío de las entregas de un comprobante. */
class DeliveryApiIT extends AbstractDeliveryIT {

	@Autowired
	FiscalDocumentRepository documents;

	@Autowired
	DeliveryRepository repository;

	@Autowired
	OutboxWriter outbox;

	@Autowired
	AuditLog audit;

	String url() {
		return "/api/v1/documents/" + document + "/deliveries";
	}

	long requestedEvents() {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?"
				+ " AND event_type = 'DELIVERY_REQUESTED' AND dispatched_at IS NULL", Long.class, document);
	}

	@Test
	void deliveriesAreListedNewestFirst() throws Exception {
		deliveries.process(s.tenant(), document);
		String other = "otra-" + UUID.randomUUID() + "@cliente.test";
		mvc.perform(json(post(url()), s.owner(), "{\"email\":\"" + other + "\"}")).andExpect(status().isAccepted());

		mvc.perform(as(get(url()), s.owner()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].recipient").value(other))
			.andExpect(jsonPath("$[0].status").value("PENDING"))
			.andExpect(jsonPath("$[0].automatic").value(false))
			.andExpect(jsonPath("$[0].attemptCount").value(0))
			.andExpect(jsonPath("$[0].sentAt").doesNotExist())
			.andExpect(jsonPath("$[1].channel").value("EMAIL"))
			.andExpect(jsonPath("$[1].recipient").value(email))
			.andExpect(jsonPath("$[1].status").value("SENT"))
			.andExpect(jsonPath("$[1].automatic").value(true))
			.andExpect(jsonPath("$[1].attemptCount").value(1))
			.andExpect(jsonPath("$[1].createdAt").isNotEmpty())
			.andExpect(jsonPath("$[1].sentAt").isNotEmpty());
	}

	@Test
	void resendToTheCustomerAddress() throws Exception {
		deliveries.process(s.tenant(), document);

		mvc.perform(as(post(url()), s.owner()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.id").isNotEmpty())
			.andExpect(jsonPath("$.recipient").value(email))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.automatic").value(false));

		assertThat(deliveryCount()).isEqualTo(2);
		assertThat(requestedEvents()).isEqualTo(1);
		// La auditoría registra quién lo pidió y qué entrega es, no la dirección.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND tenant_id = ?"
				+ " AND action = 'DELIVERY_REQUESTED' AND metadata::text LIKE '%deliveryId%'"
				+ " AND metadata::text NOT LIKE '%@%'", Long.class, document, s.tenant()))
			.isEqualTo(1);

		// Lo que hará el worker al recibir el evento.
		assertThat(deliveries.process(s.tenant(), document)).isEqualTo(1);
		assertThat(smtp.messages(email)).hasSize(2);
	}

	@Test
	void resendToAnotherAddress() throws Exception {
		String other = "otra-" + UUID.randomUUID() + "@cliente.test";

		mvc.perform(json(post(url()), s.owner(), "{\"email\":\"" + other + "\"}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.recipient").value(other));

		// Se envían la entrega automática al cliente y la pedida a la otra dirección.
		assertThat(deliveries.process(s.tenant(), document)).isEqualTo(2);
		assertThat(smtp.messages(other)).hasSize(1);
		assertThat(smtp.messages(email)).hasSize(1);
	}

	@Test
	void invalidAddressIsRejected() throws Exception {
		for (String body : new String[] { "{\"email\":\"no-es-un-correo\"}",
				"{\"email\":\"a@b.test\\r\\nBcc: x@evil.test\"}", "{\"email\":\"Nombre <a@b.test>\"}",
				"{\"email\":\"a@b.test, c@d.test\"}", "{\"email\":\"grupo:a@b.test,c@d.test;\"}", "{\"email\":\"" + "x".repeat(250) + "@b.test\"}" }) {
			mvc.perform(json(post(url()), s.owner(), body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
		}
		assertThat(deliveryCount()).isZero();
		assertThat(requestedEvents()).isZero();
	}

	@Test
	void documentWithoutAnyAddressNeedsOne() throws Exception {
		BillingScenario other = newBillingScenario();
		uploadCertificate(other.owner(), other.taxpayer(), TestCertificates.valid());
		jdbc.update("UPDATE customers SET email = NULL WHERE id = ?", other.customer());
		UUID invoice = issueInvoice(other.owner(), other.invoiceJson("1", "11.50"));
		pipeline.advance(other.tenant(), invoice);
		String target = "/api/v1/documents/" + invoice + "/deliveries";

		mvc.perform(as(post(target), other.owner()))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("DELIVERY_RECIPIENT_MISSING"));
		mvc.perform(json(post(target), other.owner(), "{\"email\":\" \"}"))
			.andExpect(status().isUnprocessableEntity());

		mvc.perform(json(post(target), other.owner(), "{\"email\":\"nuevo-" + UUID.randomUUID() + "@cliente.test\"}"))
			.andExpect(status().isAccepted());
	}

	@Test
	void notAuthorizedDocumentCannotBeDelivered() throws Exception {
		UUID reserved = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));

		mvc.perform(as(post("/api/v1/documents/" + reserved + "/deliveries"), s.owner()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_AUTHORIZED"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE document_id = ?", Long.class, reserved))
			.isZero();
	}

	@Test
	void limitPerDocument() throws Exception {
		// El tope por comprobante es 10, contando la entrega automática.
		for (int i = 0; i < 10; i++) {
			mvc.perform(as(post(url()), s.owner())).andExpect(status().isAccepted());
		}

		mvc.perform(as(post(url()), s.owner()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("DELIVERY_LIMIT_REACHED"));
		assertThat(deliveryCount()).isEqualTo(10);
	}

	@Test
	void billerCanResendAndAuditorCanOnlyList() throws Exception {
		mvc.perform(as(post(url()), memberToken(s.tenant(), "BILLER"))).andExpect(status().isAccepted());

		String auditor = memberToken(s.tenant(), "AUDITOR");
		mvc.perform(as(post(url()), auditor)).andExpect(status().isForbidden());
		mvc.perform(as(get(url()), auditor)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void foreignDocumentIsNotFound() throws Exception {
		BillingScenario other = newBillingScenario();

		mvc.perform(as(get(url()), other.owner())).andExpect(status().isNotFound());
		mvc.perform(as(post(url()), other.owner())).andExpect(status().isNotFound());
		mvc.perform(as(get("/api/v1/documents/" + UUID.randomUUID() + "/deliveries"), s.owner()))
			.andExpect(status().isNotFound());
		assertThat(deliveryCount()).isZero();
	}

	@Test
	void resendIsRefusedWhileEmailIsDisabled() {
		DeliveryProperties disabled = new DeliveryProperties(new DeliveryProperties.Email(false, null),
				Duration.ofMinutes(1), Duration.ofHours(6), 8, Duration.ofMinutes(2), 10);
		DeliveryController offline = new DeliveryController(documents, repository, outbox, audit, disabled);
		UUID user = jdbc.queryForObject("SELECT created_by FROM fiscal_documents WHERE id = ?", UUID.class, document);

		assertThatThrownBy(() -> offline.request(new TenantAccess(user, s.tenant(), Role.OWNER), document, null))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.errorCode()).isEqualTo("DELIVERY_DISABLED"));
		assertThat(deliveryCount()).isZero();
	}

}
