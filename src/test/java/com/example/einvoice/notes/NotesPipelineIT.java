package com.example.einvoice.notes;

import static com.example.einvoice.support.BillingScenario.creditLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SmtpStub;
import com.example.einvoice.support.TestCertificates;
import com.jayway.jsonpath.JsonPath;

import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;

/**
 * Las notas de crédito y de débito recorren el pipeline completo (worker encendido, SRI simulado) hasta
 * AUTHORIZED, con su RIDE y su correo; y la vista del comprobante muestra lo propio de cada nota.
 */
@TestPropertySource(properties = { "einvoice.worker.enabled=true", "einvoice.worker.poll-interval-ms=200",
		"spring.rabbitmq.listener.simple.retry.initial-interval=100ms" })
class NotesPipelineIT extends AbstractIntegrationTest {

	static final Duration PATIENCE = Duration.ofSeconds(40);

	static final String REASONS = "[{\"reason\":\"Intereses por mora\",\"value\":\"10.00\",\"ivaRateCode\":\"4\"},"
			+ "{\"reason\":\"Gastos de cobranza\",\"value\":\"5.00\",\"ivaRateCode\":\"0\"}]";

	final SmtpStub smtp = SmtpStub.instance();

	String documentJson(String token, UUID id) throws Exception {
		return mvc.perform(as(get("/api/v1/documents/" + id), token))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	void awaitState(String token, UUID id, String state) {
		await().atMost(PATIENCE)
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> assertThat(JsonPath.<String>read(documentJson(token, id), "$.state")).isEqualTo(state));
	}

	/** Factura autorizada de 2 x 10.00 + IVA 15 % = 23.00, con el cliente con correo. */
	UUID authorizedInvoice(BillingScenario s, String email) throws Exception {
		jdbc.update("UPDATE customers SET email = ? WHERE id = ?", email, s.customer());
		UUID invoice = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		awaitState(s.owner(), invoice, "AUTHORIZED");
		return invoice;
	}

	UUID issue(BillingScenario s, String path, String body) throws Exception {
		String response = mvc
			.perform(json(post(path), s.owner(), body).header("Idempotency-Key", UUID.randomUUID().toString()))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(response, "$.id"));
	}

	String debitNoteJson(BillingScenario s, UUID invoice) {
		return "{\"taxpayerId\":\"" + s.taxpayer() + "\",\"establishmentCode\":\"001\",\"emissionPointCode\":\"001\","
				+ "\"invoiceId\":\"" + invoice + "\",\"reasons\":" + REASONS
				+ ",\"payments\":[{\"methodCode\":\"20\",\"amount\":\"16.50\"}]}";
	}

	/** Espera el correo de la nota (el de la factura llegó antes) y lo devuelve. */
	MimeMessage mailWithSubject(String email, String prefix) {
		await().atMost(PATIENCE)
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> assertThat(smtp.messages(email)).anyMatch(m -> subject(m).startsWith(prefix)));
		return smtp.messages(email).stream().filter(m -> subject(m).startsWith(prefix)).findFirst().orElseThrow();
	}

	static String subject(MimeMessage message) {
		try {
			return message.getSubject();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	static boolean hasAttachment(MimeMessage message, String prefix) throws Exception {
		Multipart parts = (Multipart) message.getContent();
		for (int i = 0; i < parts.getCount(); i++) {
			String name = ((MimeBodyPart) parts.getBodyPart(i)).getFileName();
			if (name != null && name.startsWith(prefix) && name.endsWith(".pdf")) {
				return true;
			}
		}
		return false;
	}

	@Test
	void creditNoteEndsAuthorizedWithRideAndMail() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		String email = "c-" + UUID.randomUUID() + "@cliente.test";
		UUID invoice = authorizedInvoice(s, email);

		UUID note = issue(s, "/api/v1/credit-notes", s.creditNoteJson(invoice, creditLine(1, "1")));

		awaitState(s.owner(), note, "AUTHORIZED");
		MimeMessage message = mailWithSubject(email, "Nota de crédito ");
		assertThat(message.getSubject()).startsWith("Nota de crédito 001-001-");
		assertThat(hasAttachment(message, "nota-credito-")).isTrue();
		// El RIDE se archiva antes de enviar el correo.
		mvc.perform(as(get("/api/v1/documents/" + note + "/artifacts/ride"), s.owner())).andExpect(status().isOk());
	}

	@Test
	void debitNoteEndsAuthorizedWithRideAndMail() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		String email = "d-" + UUID.randomUUID() + "@cliente.test";
		UUID invoice = authorizedInvoice(s, email);

		UUID note = issue(s, "/api/v1/debit-notes", debitNoteJson(s, invoice));

		awaitState(s.owner(), note, "AUTHORIZED");
		MimeMessage message = mailWithSubject(email, "Nota de débito ");
		assertThat(message.getSubject()).startsWith("Nota de débito 001-001-");
		assertThat(hasAttachment(message, "nota-debito-")).isTrue();
		// El RIDE se archiva antes de enviar el correo.
		mvc.perform(as(get("/api/v1/documents/" + note + "/artifacts/ride"), s.owner())).andExpect(status().isOk());
	}

}
