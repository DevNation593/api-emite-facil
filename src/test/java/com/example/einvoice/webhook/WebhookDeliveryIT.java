package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.certificate.SecretCipher;
import com.example.einvoice.delivery.DeliveryService;
import com.example.einvoice.support.SriSoapSamples;
import com.example.einvoice.support.SriSoapSamples.Msg;
import com.example.einvoice.support.SriStub.Reply;
import com.example.einvoice.support.WebhookReceiverStub;
import com.example.einvoice.support.WebhookReceiverStub.Captured;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import com.example.einvoice.certificate.SecretCipherException;
import com.example.einvoice.orchestration.RetryPolicy;

class WebhookDeliveryIT extends AbstractWebhookIT {

	static final String[] ALL = { "document.received", "document.authorized", "document.rejected", "document.delivered" };

	@Autowired
	WebhookRepository repository;

	@Autowired
	WebhookSender sender;

	@Autowired
	SecretCipher cipher;

	@Autowired
	WebhookProperties properties;

	@Autowired
	JsonMapper json;

	@Autowired
	DeliveryService deliveries;

	@Test
	void anAuthorizedInvoiceAnnouncesItsReceptionAndItsAuthorization() throws Exception {
		Subscribed subscribed = subscribe(ALL);
		UUID document = invoiceIn("AUTHORIZED");

		assertThat(webhooks.process(s.tenant(), document)).isEqualTo(2);

		List<Captured> received = receiver.received(subscribed.name());
		assertThat(received).extracting(AbstractWebhookIT::eventType)
			.containsExactlyInAnyOrder("document.received", "document.authorized");
		for (Captured request : received) {
			assertThat(request.method()).isEqualTo("POST");
			assertThat(request.target()).endsWith("?ficha=ficha-" + subscribed.name());
			assertThat(request.header("content-type")).isEqualTo("application/json");
			assertThat(signedWith(request, subscribed.secret())).isTrue();
			assertThat(request.header("webhook-signature")).doesNotContain(" ");
			assertThat(request.header("webhook-id")).isEqualTo(JsonPath.<String>read(request.text(), "$.eventId"));
			assertThat(Long.parseLong(request.header("webhook-timestamp")))
				.isBetween(Instant.now().getEpochSecond() - 60, Instant.now().getEpochSecond() + 5);
			assertThat(JsonPath.<String>read(request.text(), "$.tenantId")).isEqualTo(s.tenant().toString());
			assertThat(JsonPath.<String>read(request.text(), "$.document.id")).isEqualTo(document.toString());
			assertThat(JsonPath.<String>read(request.text(), "$.document.accessKey")).isEqualTo(key(document));
			assertThat(JsonPath.<String>read(request.text(), "$.document.documentType")).isEqualTo("01");
			assertThat(JsonPath.<String>read(request.text(), "$.document.number")).matches("001-001-\\d{9}");
			assertThat(OffsetDateTime.parse(JsonPath.<String>read(request.text(), "$.occurredAt")))
				.isBetween(OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now().plusSeconds(5));
			// Solo identificadores y estado: ni el cliente, ni su correo, ni importes.
			assertThat(request.text()).doesNotContain("1712345678", "23.00", "23,00", "@", "customer", "total");
		}
		Captured reception = received.stream().filter(r -> eventType(r).equals("document.received")).findFirst().orElseThrow();
		Captured authorization = received.stream().filter(r -> eventType(r).equals("document.authorized")).findFirst().orElseThrow();
		assertThat(JsonPath.<String>read(reception.text(), "$.document.state")).isEqualTo("RECEIVED");
		// El aviso de recepción no adelanta la autorización aunque salga después de ella.
		assertThat(JsonPath.<Object>read(reception.text(), "$.document.authorizationNumber")).isNull();
		assertThat(JsonPath.<String>read(authorization.text(), "$.document.state")).isEqualTo("AUTHORIZED");
		assertThat(JsonPath.<String>read(authorization.text(), "$.document.authorizationNumber")).isEqualTo(key(document));
		assertThat(JsonPath.<String>read(authorization.text(), "$.document.authorizedAt")).isNotBlank();
		assertThat(reception.header("webhook-id")).isNotEqualTo(authorization.header("webhook-id"));
		assertThat(notifications(document)).allSatisfy(row -> {
			assertThat(row).containsEntry("status", "SENT").containsEntry("last_status", 200).containsEntry("attempt_count", 1);
			assertThat(row.get("next_attempt_at")).isNull();
		});
	}

	@Test
	void aRejectedDocumentSaysWhichRejectionItWas() throws Exception {
		Subscribed subscribed = subscribe("document.rejected");
		UUID returned = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		sri.onReception(key(returned), Reply.xml(SriSoapSamples.returned(key(returned), Msg.error("35", "DOCUMENTO INVÁLIDO"))));
		advanceTo(returned, "RETURNED");
		UUID notAuthorized = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		sri.onAuthorization(key(notAuthorized),
				Reply.xml(SriSoapSamples.notAuthorized(key(notAuthorized), "NO AUTORIZADO", Msg.error("39", "FIRMA INVALIDA"))));
		advanceTo(notAuthorized, "NOT_AUTHORIZED");

		webhooks.process(s.tenant(), returned);
		webhooks.process(s.tenant(), notAuthorized);

		List<Captured> received = receiver.received(subscribed.name());
		assertThat(received).extracting(AbstractWebhookIT::eventType).containsOnly("document.rejected");
		assertThat(received).extracting(request -> JsonPath.<String>read(request.text(), "$.document.state"))
			.containsExactlyInAnyOrder("RETURNED", "NOT_AUTHORIZED");
		// Los motivos del SRI no viajan en el aviso.
		assertThat(received).allSatisfy(request -> assertThat(request.text()).doesNotContain("INVÁLIDO", "FIRMA"));
	}

	@Test
	void anEmailedInvoiceAnnouncesItsDelivery() throws Exception {
		Subscribed subscribed = subscribe("document.delivered");
		jdbc.update("UPDATE customers SET email = ? WHERE id = ?", "c-" + UUID.randomUUID() + "@cliente.test", s.customer());
		UUID document = invoiceIn("AUTHORIZED");
		assertThat(notifications(document)).isEmpty();

		assertThat(deliveries.process(s.tenant(), document)).isEqualTo(1);
		webhooks.process(s.tenant(), document);

		List<Captured> received = receiver.received(subscribed.name());
		assertThat(received).hasSize(1);
		assertThat(eventType(received.get(0))).isEqualTo("document.delivered");
		assertThat(JsonPath.<String>read(received.get(0).text(), "$.document.state")).isEqualTo("AUTHORIZED");
		assertThat(received.get(0).text()).doesNotContain("@cliente.test");
		// Procesar otra vez las entregas no vuelve a anunciar un correo que ya salió.
		deliveries.process(s.tenant(), document);
		assertThat(notifications(document)).hasSize(1);
	}

	@Test
	void eachSubscriptionGetsOnlyItsEventsAndWithoutSubscriptionsNothingIsRecorded() throws Exception {
		UUID before = invoiceIn("AUTHORIZED");
		assertThat(notifications(before)).isEmpty();

		Subscribed onlyAuthorized = subscribe("document.authorized");
		Subscribed everything = subscribe(ALL);
		UUID document = invoiceIn("AUTHORIZED");
		webhooks.process(s.tenant(), document);

		assertThat(receiver.received(onlyAuthorized.name())).extracting(AbstractWebhookIT::eventType)
			.containsExactly("document.authorized");
		assertThat(receiver.received(everything.name())).hasSize(2);
		// El mismo evento lleva el mismo identificador a todas las suscripciones.
		String authorizedId = receiver.received(onlyAuthorized.name()).get(0).header("webhook-id");
		assertThat(receiver.received(everything.name())).extracting(request -> request.header("webhook-id"))
			.contains(authorizedId);
		// Nada de lo anterior a la suscripción se envía después.
		assertThat(webhooks.process(s.tenant(), before)).isZero();
	}

	@Test
	void aFailedAttemptIsRetriedLaterWithTheSameIdentifier() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		receiver.on(subscribed.name(), WebhookReceiverStub.Reply.status(500), WebhookReceiverStub.Reply.status(200));
		UUID document = invoiceIn("AUTHORIZED");

		assertThat(webhooks.process(s.tenant(), document)).isZero();

		Map<String, Object> failed = notification(document, "document.authorized");
		assertThat(failed).containsEntry("status", "PENDING").containsEntry("attempt_count", 1).containsEntry("last_status", 500);
		assertThat((String) failed.get("last_error")).contains("500");
		assertThat(jdbc.queryForObject("""
				SELECT retry_after BETWEEN now() + interval '10 seconds' AND now() + interval '2 minutes'
				   AND next_attempt_at = retry_after FROM webhook_deliveries WHERE id = ?
				""", Boolean.class, failed.get("id"))).isTrue();
		assertThat(subscription(subscribed.id())).containsEntry("consecutive_failures", 1);
		// Antes de que pase la espera no se insiste.
		assertThat(webhooks.process(s.tenant(), document)).isZero();
		assertThat(receiver.received(subscribed.name())).hasSize(1);

		makeDue(document);
		assertThat(webhooks.process(s.tenant(), document)).isEqualTo(1);

		List<Captured> received = receiver.received(subscribed.name());
		assertThat(received).hasSize(2);
		assertThat(received.get(1).header("webhook-id")).isEqualTo(received.get(0).header("webhook-id"));
		assertThat(received.get(1).body()).isEqualTo(received.get(0).body());
		assertThat(notification(document, "document.authorized")).containsEntry("status", "SENT").containsEntry("attempt_count", 2);
		assertThat(subscription(subscribed.id())).containsEntry("consecutive_failures", 0);
	}

	@Test
	void afterTheLastAttemptTheNotificationFails() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		receiver.on(subscribed.name(), WebhookReceiverStub.Reply.status(503));
		UUID document = invoiceIn("AUTHORIZED");
		jdbc.update("UPDATE webhook_deliveries SET attempt_count = ? WHERE document_id = ?", properties.retryMaxAttempts() - 1,
				document);

		webhooks.process(s.tenant(), document);

		Map<String, Object> row = notification(document, "document.authorized");
		assertThat(row).containsEntry("status", "FAILED")
			.containsEntry("attempt_count", properties.retryMaxAttempts())
			.containsEntry("last_status", 503);
		assertThat(row.get("next_attempt_at")).isNull();
		assertThat(webhooks.process(s.tenant(), document)).isZero();
		assertThat(receiver.received(subscribed.name())).hasSize(1);
	}

	@Test
	void theRetriesOfANotificationSpanAboutADay() {
		// Lo que se le dice al integrador: un receptor caído una noche entera no pierde avisos.
		Duration total = Duration.ZERO;
		for (int attempt = 1; attempt < properties.retryMaxAttempts(); attempt++) {
			total = total.plus(RetryPolicy.delay(attempt, properties.retryBackoff(), properties.retryMaxBackoff(), 0.5));
		}

		assertThat(total).isBetween(Duration.ofHours(20), Duration.ofHours(30));
	}

	@Test
	void afterRotatingBothSecretsSignUntilTheOldOneExpires() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		String rotated = JsonPath.read(
				mvc.perform(as(post("/api/v1/webhooks/subscriptions/" + subscribed.id() + "/secret"), s.owner()))
					.andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString(),
				"$.secret");
		webhooks.process(s.tenant(), invoiceIn("AUTHORIZED"));

		Captured during = receiver.received(subscribed.name()).get(0);
		assertThat(during.header("webhook-signature").split(" ")).hasSize(2);
		assertThat(signedWith(during, rotated)).isTrue();
		assertThat(signedWith(during, subscribed.secret())).isTrue();
		// La del secreto vigente va primero.
		assertThat(during.header("webhook-signature").split(" ")[0]).isEqualTo(WebhookSigner
			.sign(WebhookSigner.key(rotated), during.header("webhook-id"), Long.parseLong(during.header("webhook-timestamp")), during.body()));

		jdbc.update("UPDATE webhook_subscriptions SET previous_secret_expires_at = now() - interval '1 minute' WHERE id = ?",
				subscribed.id());
		webhooks.process(s.tenant(), invoiceIn("AUTHORIZED"));

		Captured after = receiver.received(subscribed.name()).get(1);
		assertThat(after.header("webhook-signature")).doesNotContain(" ");
		assertThat(signedWith(after, rotated)).isTrue();
		assertThat(signedWith(after, subscribed.secret())).isFalse();
	}

	@Test
	void aDisabledOrDeletedSubscriptionReceivesNothing() throws Exception {
		Subscribed disabled = subscribe(ALL);
		Subscribed deleted = subscribe(ALL);
		UUID pending = invoiceIn("AUTHORIZED");
		assertThat(notifications(pending)).hasSize(4);
		mvc.perform(json(put("/api/v1/webhooks/subscriptions/" + disabled.id()), s.owner(),
				"{\"url\":\"" + receiver.url(disabled.name()) + "\",\"eventTypes\":[\"document.authorized\"],\"enabled\":false}"))
			.andExpect(status().isOk());
		mvc.perform(as(delete("/api/v1/webhooks/subscriptions/" + deleted.id()), s.owner())).andExpect(status().isNoContent());

		assertThat(webhooks.process(s.tenant(), pending)).isZero();

		assertThat(receiver.received(disabled.name())).isEmpty();
		assertThat(receiver.received(deleted.name())).isEmpty();
		// Los de la borrada se fueron con ella; los de la apagada quedan como fallidos, sin reintento.
		assertThat(notifications(pending)).hasSize(2).allSatisfy(row -> {
			assertThat(row).containsEntry("status", "FAILED").containsEntry("subscription_id", disabled.id());
			assertThat((String) row.get("last_error")).contains("desactivada");
			assertThat(row.get("next_attempt_at")).isNull();
		});
		// Y lo que pase después ni se anota.
		UUID later = invoiceIn("AUTHORIZED");
		assertThat(notifications(later)).isEmpty();
	}

	@Test
	void aSubscriptionThatOnlyFailsDisablesItselfAndASuccessStartsTheCountAgain() throws Exception {
		Subscribed failing = subscribe("document.authorized");
		receiver.on(failing.name(), WebhookReceiverStub.Reply.status(500));
		// Lleva días fallando, no un rato.
		jdbc.update("UPDATE webhook_subscriptions SET consecutive_failures = 99, failing_since = now() - interval '4 days' "
				+ "WHERE id = ?", failing.id());
		UUID document = invoiceIn("AUTHORIZED");

		webhooks.process(s.tenant(), document);

		assertThat(subscription(failing.id())).containsEntry("status", "DISABLED")
			.containsEntry("disabled_reason", "TOO_MANY_FAILURES")
			.containsEntry("consecutive_failures", 100);
		assertThat(notification(document, "document.authorized")).containsEntry("status", "FAILED");
		assertThat(notifications(invoiceIn("AUTHORIZED"))).isEmpty();

		Subscribed recovering = subscribe("document.authorized");
		jdbc.update("UPDATE webhook_subscriptions SET consecutive_failures = 42 WHERE id = ?", recovering.id());
		webhooks.process(s.tenant(), invoiceIn("AUTHORIZED"));
		assertThat(subscription(recovering.id())).containsEntry("status", "ACTIVE").containsEntry("consecutive_failures", 0);
	}

	@Test
	void aShortOutageOnABusyCompanyDoesNotSwitchTheSubscriptionOff() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		receiver.on(subscribed.name(), WebhookReceiverStub.Reply.status(503), WebhookReceiverStub.Reply.status(503),
				WebhookReceiverStub.Reply.status(200));
		UUID first = invoiceIn("AUTHORIZED");
		webhooks.process(s.tenant(), first);
		// El primer fallo marca desde cuándo falla.
		assertThat(jdbc.queryForObject("SELECT failing_since > now() - interval '1 minute' FROM webhook_subscriptions WHERE id = ?",
				Boolean.class, subscribed.id())).isTrue();

		// Muchos comprobantes en pocos minutos: cientos de intentos fallidos, pero el receptor lleva caído un rato.
		jdbc.update("UPDATE webhook_subscriptions SET consecutive_failures = 500 WHERE id = ?", subscribed.id());
		webhooks.process(s.tenant(), invoiceIn("AUTHORIZED"));

		assertThat(subscription(subscribed.id())).containsEntry("status", "ACTIVE").containsEntry("consecutive_failures", 501);
		// Vuelve: la cuenta y la marca se borran.
		webhooks.process(s.tenant(), invoiceIn("AUTHORIZED"));
		assertThat(subscription(subscribed.id())).containsEntry("consecutive_failures", 0).containsEntry("failing_since", null);
	}

	@Test
	void aFaultOfOursIsRetriedWithoutBlamingTheReceiver() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		UUID document = invoiceIn("AUTHORIZED");
		// Un worker con la clave maestra equivocada no puede descifrar el secreto para firmar.
		SecretCipher wrongKey = new SecretCipher() {
			@Override
			public byte[] encrypt(byte[] plaintext, byte[] context) {
				throw new SecretCipherException("clave equivocada", null);
			}

			@Override
			public byte[] decrypt(byte[] ciphertext, byte[] context) {
				throw new SecretCipherException("clave equivocada", null);
			}
		};
		WebhookDeliveryService broken = new WebhookDeliveryService(repository, sender,
				new WebhookUrlPolicy(true, true, InetAddress::getAllByName), wrongKey, properties, json);

		assertThat(broken.process(s.tenant(), document)).isZero();

		assertThat(receiver.received(subscribed.name())).isEmpty();
		assertThat(notification(document, "document.authorized")).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
		assertThat(subscription(subscribed.id())).containsEntry("consecutive_failures", 0).containsEntry("status", "ACTIVE");
		// Corregida la clave, sale.
		makeDue(document);
		assertThat(webhooks.process(s.tenant(), document)).isEqualTo(1);
	}

	@Test
	void processingTheSameDocumentAgainDoesNotRepeatWhatWasSent() throws Exception {
		Subscribed subscribed = subscribe(ALL);
		UUID document = invoiceIn("AUTHORIZED");

		assertThat(webhooks.process(s.tenant(), document)).isEqualTo(2);
		assertThat(webhooks.process(s.tenant(), document)).isZero();
		// Ni con la empresa equivocada.
		assertThat(webhooks.process(UUID.randomUUID(), document)).isZero();

		assertThat(receiver.received(subscribed.name())).hasSize(2);
	}

	@Test
	void aSendLeftHalfwayIsTakenUpAgainOnlyAfterItsLease() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		UUID document = invoiceIn("AUTHORIZED");
		// Otro proceso lo reservó hace un momento y aún no anotó el resultado.
		jdbc.update("UPDATE webhook_deliveries SET status = 'SENDING', sending_since = now(), attempt_count = 1 "
				+ "WHERE document_id = ?", document);

		assertThat(webhooks.process(s.tenant(), document)).isZero();
		assertThat(receiver.received(subscribed.name())).isEmpty();

		// Pasado el plazo se da por abandonado: sale otra vez, con el mismo identificador.
		jdbc.update("UPDATE webhook_deliveries SET sending_since = now() - interval '3 minutes' WHERE document_id = ?", document);
		assertThat(webhooks.process(s.tenant(), document)).isEqualTo(1);
		assertThat(notification(document, "document.authorized")).containsEntry("status", "SENT").containsEntry("attempt_count", 2);
	}

	@Test
	void anAddressThatIsNoLongerPublicIsNotContacted() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		UUID document = invoiceIn("AUTHORIZED");
		// Como en producción: el receptor (local) ya no pasa la comprobación.
		WebhookDeliveryService strict = new WebhookDeliveryService(repository, sender,
				new WebhookUrlPolicy(true, false, InetAddress::getAllByName), cipher, properties, json);

		assertThat(strict.process(s.tenant(), document)).isZero();

		assertThat(receiver.received(subscribed.name())).isEmpty();
		// No se conecta, pero tampoco se da por perdido: una resolución de nombres averiada un rato no cuesta avisos.
		Map<String, Object> row = notification(document, "document.authorized");
		assertThat(row).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
		assertThat(row.get("retry_after")).isNotNull();
		assertThat((String) row.get("last_error")).contains("público").doesNotContain("127.0.0.1", subscribed.name());
		assertThat(row.get("last_status")).isNull();
		assertThat(subscription(subscribed.id())).containsEntry("consecutive_failures", 1);
	}

	@Test
	void aNameThatDoesNotResolveRightNowIsTriedAgainLater() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		UUID document = invoiceIn("AUTHORIZED");
		WebhookDeliveryService offline = new WebhookDeliveryService(repository, sender, new WebhookUrlPolicy(true, true, host -> {
			throw new UnknownHostException(host);
		}), cipher, properties, json);

		assertThat(offline.process(s.tenant(), document)).isZero();

		assertThat(receiver.received(subscribed.name())).isEmpty();
		assertThat(notification(document, "document.authorized")).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
	}

	@Test
	void neitherTheSecretNorTheAddressLeaveTheSubscriptionsTable() throws Exception {
		Subscribed subscribed = subscribe(ALL);
		receiver.on(subscribed.name(), WebhookReceiverStub.Reply.status(500));
		UUID document = invoiceIn("AUTHORIZED");
		webhooks.process(s.tenant(), document);

		String elsewhere = String.join(" ",
				jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE tenant_id = ?", String.class, s.tenant()))
				+ String.join(" ",
						jdbc.queryForList("SELECT metadata::text FROM audit_events WHERE tenant_id = ?", String.class, s.tenant()))
				+ String.join(" ", jdbc.queryForList(
						"SELECT coalesce(last_error, '') FROM webhook_deliveries WHERE tenant_id = ?", String.class, s.tenant()));
		assertThat(elsewhere).doesNotContain(subscribed.secret(), subscribed.secret().substring(6), subscribed.name(),
				"ficha-", "127.0.0.1");
	}

}
