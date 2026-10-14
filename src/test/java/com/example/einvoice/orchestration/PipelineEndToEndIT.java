package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SriSoapSamples;
import com.example.einvoice.support.SmtpStub;
import com.example.einvoice.support.SriStub;
import com.example.einvoice.support.SriStub.Reply;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.support.WebhookReceiverStub;
import com.jayway.jsonpath.JsonPath;

import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;

/**
 * El recorrido completo con el worker encendido: outbox, RabbitMQ, generación, firma, recepción y
 * autorización, sin ninguna llamada manual al pipeline. Usa un contexto de Spring propio.
 */
@TestPropertySource(properties = { "einvoice.worker.enabled=true", "einvoice.worker.poll-interval-ms=200",
		"spring.rabbitmq.listener.simple.retry.initial-interval=100ms" })
class PipelineEndToEndIT extends AbstractIntegrationTest {

	private static final Duration PATIENCE = Duration.ofSeconds(40);

	@Autowired
	RabbitTemplate rabbit;

	final SriStub sri = SriStub.instance();

	final SmtpStub smtp = SmtpStub.instance();

	String stateByApi(String token, UUID document) throws Exception {
		String body = mvc.perform(as(get("/api/v1/documents/" + document), token))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.state");
	}

	void awaitState(String token, UUID document, String state) {
		await().atMost(PATIENCE)
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> assertThat(stateByApi(token, document)).isEqualTo(state));
	}

	@Test
	void issuedInvoiceEndsAuthorizedWithoutAnyManualStep() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());

		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));

		awaitState(s.owner(), document, "AUTHORIZED");
		String key = jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class, document);
		assertThat(sri.receptionCalls(key)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT authorization_number FROM fiscal_documents WHERE id = ?", String.class,
				document))
			.isEqualTo(key);
	}

	@Test
	void issuedInvoiceNotifiesTheSubscriberWithoutAnyManualStep() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		WebhookReceiverStub receiver = WebhookReceiverStub.instance();
		String name = "e2e-" + UUID.randomUUID();
		mvc.perform(json(post("/api/v1/webhooks/subscriptions"), s.owner(), "{\"url\":\"" + receiver.url(name)
				+ "\",\"eventTypes\":[\"document.received\",\"document.authorized\"]}"))
			.andExpect(status().isCreated());

		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));

		await().atMost(PATIENCE)
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> assertThat(receiver.received(name))
				.extracting(request -> JsonPath.<String>read(request.text(), "$.eventType"))
				.containsExactlyInAnyOrder("document.received", "document.authorized"));
		assertThat(receiver.received(name))
			.allSatisfy(request -> assertThat(JsonPath.<String>read(request.text(), "$.document.id")).isEqualTo(document.toString()));
	}

	@Test
	void processingInvoiceIsAuthorizedOnALaterPoll() throws Exception {
		// Sin certificado el worker deja la factura en SIGNATURE_FAILED: da tiempo a preparar el guion del SRI.
		BillingScenario s = newBillingScenario();
		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		awaitState(s.owner(), document, "SIGNATURE_FAILED");
		String key = jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class, document);
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.processing(key)), Reply.xml(SriSoapSamples.processing(key)),
				Reply.xml(SriSoapSamples.authorized(key)));
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());

		// El planificador la retoma al vencer el intento programado, y vuelve a consultar hasta la autorización.
		jdbc.update("UPDATE fiscal_documents SET next_attempt_at = now() WHERE id = ?", document);

		awaitState(s.owner(), document, "AUTHORIZED");
		assertThat(sri.receptionCalls(key)).isEqualTo(1);
		assertThat(sri.authorizationCalls(key)).isEqualTo(3);
	}

	@Test
	void poisonMessageEndsInTheDeadLetterQueue() throws Exception {
		Message poison = MessageBuilder.withBody("esto no es un evento".getBytes(StandardCharsets.UTF_8)).build();
		rabbit.send(MessagingConfig.EVENTS_EXCHANGE, MessagingConfig.ISSUE_ROUTING_KEY, poison);

		Message dead = rabbit.receive(MessagingConfig.DEAD_LETTER_QUEUE, PATIENCE.toMillis());

		assertThat(dead).isNotNull();
		assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("esto no es un evento");

		// El worker sigue atendiendo.
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		UUID document = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		awaitState(s.owner(), document, "AUTHORIZED");
	}

	@Test
	void issuedInvoiceIsEmailedWithoutAnyManualStep() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		String email = "c-" + UUID.randomUUID() + "@cliente.test";
		jdbc.update("UPDATE customers SET email = ? WHERE id = ?", email, s.customer());

		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));

		await().atMost(PATIENCE)
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> assertThat(smtp.messages(email)).hasSize(1));
		MimeMessage message = smtp.messages(email).get(0);
		assertThat(message.getSubject()).startsWith("Factura 001-001-");
		assertThat(((Multipart) message.getContent()).getCount()).isEqualTo(3);
		mvc.perform(as(get("/api/v1/documents/" + document + "/artifacts/ride"), s.owner()))
			.andExpect(status().isOk());
		mvc.perform(as(get("/api/v1/documents/" + document + "/artifacts/xml-authorized"), s.owner()))
			.andExpect(status().isOk());
		assertThat(stateByApi(s.owner(), document)).isEqualTo("AUTHORIZED");
		// Archivado y entregado: no queda nada programado para ese comprobante.
		await().atMost(PATIENCE)
			.untilAsserted(() -> assertThat(jdbc.queryForObject(
					"SELECT next_attempt_at IS NULL FROM fiscal_documents WHERE id = ?", Boolean.class, document))
				.isTrue());
	}

	@Test
	void emailIsRetriedByTheSchedulerAfterATemporaryFailure() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		String email = "c-" + UUID.randomUUID() + "@cliente.test";
		jdbc.update("UPDATE customers SET email = ? WHERE id = ?", email, s.customer());
		smtp.onRecipient(email, SmtpStub.Reply.failData(451, "4.3.0 Intente más tarde"), SmtpStub.Reply.accept());

		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));

		// El primer intento falla; el planificador devuelve la entrega a la cola y el segundo llega.
		await().atMost(PATIENCE)
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> assertThat(smtp.messages(email)).hasSize(1));
		assertThat(jdbc.queryForObject(
				"SELECT status || '|' || attempt_count FROM deliveries WHERE document_id = ?", String.class, document))
			.isEqualTo("SENT|2");
	}

	@Test
	void eventForAMissingDocumentIsDiscarded() throws Exception {
		String body = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"" + UUID.randomUUID()
				+ "\",\"documentId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"DOCUMENT_DUE\"}";
		rabbit.send(MessagingConfig.EVENTS_EXCHANGE, MessagingConfig.ISSUE_ROUTING_KEY,
				MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).build());

		// No es un mensaje envenenado: se descarta sin pasar por la cola de mensajes muertos.
		assertThat(rabbit.receive(MessagingConfig.DEAD_LETTER_QUEUE, 3000)).isNull();
	}

}
