package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SriStub;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.support.WebhookReceiverStub;
import com.example.einvoice.support.WebhookReceiverStub.Captured;
import com.jayway.jsonpath.JsonPath;

/** Base de las pruebas de avisos: un emisor con certificado y un receptor simulado. */
abstract class AbstractWebhookIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

	@Autowired
	WebhookDeliveryService webhooks;

	final SriStub sri = SriStub.instance();

	final WebhookReceiverStub receiver = WebhookReceiverStub.instance();

	BillingScenario s;

	/** Una suscripción recién creada: su secreto solo se conoce aquí. */
	record Subscribed(UUID id, String secret, String name) {
	}

	@BeforeEach
	void anIssuerWithCertificate() throws Exception {
		s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
	}

	Subscribed subscribe(String... eventTypes) throws Exception {
		String name = "r-" + UUID.randomUUID();
		String body = "{\"url\":\"" + receiver.url(name) + "\",\"eventTypes\":[\"" + String.join("\",\"", eventTypes) + "\"]}";
		String response = mvc.perform(json(post("/api/v1/webhooks/subscriptions"), s.owner(), body))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Subscribed(UUID.fromString(JsonPath.read(response, "$.id")), JsonPath.read(response, "$.secret"), name);
	}

	String state(UUID document) {
		return jdbc.queryForObject("SELECT state FROM fiscal_documents WHERE id = ?", String.class, document);
	}

	String key(UUID document) {
		return jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class, document);
	}

	/** Emite una factura y la lleva hasta ese estado. */
	UUID invoiceIn(String state) throws Exception {
		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		advanceTo(document, state);
		return document;
	}

	void advanceTo(UUID document, String state) {
		for (int step = 0; step < 10 && !state.equals(state(document)); step++) {
			pipeline.advance(s.tenant(), document);
		}
		assertThat(state(document)).isEqualTo(state);
	}

	/** Avisos anotados para el comprobante, del más antiguo al más reciente. */
	List<Map<String, Object>> notifications(UUID document) {
		return jdbc.queryForList("SELECT * FROM webhook_deliveries WHERE document_id = ? ORDER BY occurred_at, event_type",
				document);
	}

	Map<String, Object> notification(UUID document, String eventType) {
		return jdbc.queryForMap("SELECT * FROM webhook_deliveries WHERE document_id = ? AND event_type = ?", document,
				eventType);
	}

	Map<String, Object> subscription(UUID id) {
		return jdbc.queryForMap("SELECT * FROM webhook_subscriptions WHERE id = ?", id);
	}

	/** Deja los avisos pendientes del comprobante listos para salir ya. */
	void makeDue(UUID document) {
		jdbc.update("UPDATE webhook_deliveries SET retry_after = NULL, next_attempt_at = now() WHERE document_id = ? "
				+ "AND status = 'PENDING'", document);
	}

	static String eventType(Captured request) {
		return JsonPath.read(request.text(), "$.eventType");
	}

	/** Si alguna de las firmas del aviso es la de ese secreto. */
	static boolean signedWith(Captured request, String secret) {
		String expected = WebhookSigner.sign(WebhookSigner.key(secret), request.header("webhook-id"),
				Long.parseLong(request.header("webhook-timestamp")), request.body());
		return List.of(request.header("webhook-signature").split(" ")).contains(expected);
	}

}
