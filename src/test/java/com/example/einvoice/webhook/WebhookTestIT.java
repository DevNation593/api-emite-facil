package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.WebhookReceiverStub.Captured;
import com.example.einvoice.support.WebhookReceiverStub.Reply;
import com.jayway.jsonpath.JsonPath;

/** El aviso de prueba: probar una dirección sin emitir un comprobante. */
class WebhookTestIT extends AbstractWebhookIT {

	@Autowired
	DueWebhookScheduler scheduler;

	ResultActions test(String token, UUID subscription) throws Exception {
		return mvc.perform(as(post("/api/v1/webhooks/subscriptions/" + subscription + "/test"), token));
	}

	Map<String, Object> delivery(Object id) {
		return jdbc.queryForMap("SELECT * FROM webhook_deliveries WHERE id = ?", id);
	}

	UUID requested(Subscribed subscribed) throws Exception {
		String body = test(s.owner(), subscribed.id()).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.eventType").value("webhook.test"))
			.andExpect(jsonPath("$.documentId").doesNotExist())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	@Test
	void aTestNotificationArrivesSignedWithoutAnyDocument() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		UUID id = requested(subscribed);

		assertThat(webhooks.processTests(s.tenant(), subscribed.id())).isEqualTo(1);

		List<Captured> received = receiver.received(subscribed.name());
		assertThat(received).hasSize(1);
		Captured request = received.get(0);
		assertThat(signedWith(request, subscribed.secret())).isTrue();
		assertThat(eventType(request)).isEqualTo("webhook.test");
		Map<String, Object> body = JsonPath.read(request.text(), "$");
		assertThat(body).containsOnlyKeys("eventId", "eventType", "occurredAt", "tenantId", "subscriptionId")
			.containsEntry("tenantId", s.tenant().toString())
			.containsEntry("subscriptionId", subscribed.id().toString());
		assertThat(request.header("webhook-id")).isEqualTo(body.get("eventId"));
		assertThat(delivery(id)).containsEntry("status", "SENT").containsEntry("last_status", 200);
		mvc.perform(as(get("/api/v1/webhooks/subscriptions/" + subscribed.id() + "/deliveries"), s.owner()))
			.andExpect(jsonPath("$.items[0].eventType").value("webhook.test"))
			.andExpect(jsonPath("$.items[0].status").value("SENT"));
	}

	@Test
	void aFailedTestIsTriedOnceAndDoesNotCountAgainstTheSubscription() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		receiver.on(subscribed.name(), Reply.status(500));
		UUID id = requested(subscribed);

		assertThat(webhooks.processTests(s.tenant(), subscribed.id())).isZero();

		assertThat(delivery(id)).containsEntry("status", "FAILED")
			.containsEntry("attempt_count", 1)
			.containsEntry("last_status", 500)
			.containsEntry("next_attempt_at", null);
		assertThat(subscription(subscribed.id())).containsEntry("consecutive_failures", 0)
			.containsEntry("failing_since", null);
		// Para probar otra vez se pide otro aviso de prueba, no se reintenta este.
		mvc.perform(as(post("/api/v1/webhooks/deliveries/" + id + "/retry"), s.owner()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("WEBHOOK_DELIVERY_NOT_RETRYABLE"));
		requested(subscribed);
	}

	@Test
	void onlyOneTestAtATimePerSubscription() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		requested(subscribed);

		test(s.owner(), subscribed.id()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("WEBHOOK_TEST_PENDING"));
		// Otra suscripción no está esperando nada.
		requested(subscribe("document.received"));

		webhooks.processTests(s.tenant(), subscribed.id());
		requested(subscribed);
	}

	@Test
	void aDisabledSubscriptionIsNotTested() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		jdbc.update("UPDATE webhook_subscriptions SET status = 'DISABLED', disabled_reason = 'MANUAL' WHERE id = ?",
				subscribed.id());

		test(s.owner(), subscribed.id()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("WEBHOOK_SUBSCRIPTION_DISABLED"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE subscription_id = ?", Integer.class,
				subscribed.id())).isZero();
	}

	@Test
	void onlyOwnersAndAdministratorsOfThatCompanyRequestATest() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		for (String role : List.of("BILLER", "AUDITOR")) {
			test(memberToken(s.tenant(), role), subscribed.id()).andExpect(status().isForbidden());
		}
		BillingScenario stranger = newBillingScenario();
		test(stranger.owner(), subscribed.id()).andExpect(status().isNotFound());
		test(s.owner(), UUID.randomUUID()).andExpect(status().isNotFound());

		test(memberToken(s.tenant(), "ADMIN"), subscribed.id()).andExpect(status().isAccepted());
		List<Map<String, Object>> audit = jdbc.queryForList(
				"SELECT metadata::text AS metadata FROM audit_events WHERE tenant_id = ? AND action = 'WEBHOOK_TEST_REQUESTED'",
				s.tenant());
		assertThat(audit).hasSize(1);
		assertThat(audit.get(0).get("metadata").toString()).doesNotContain(receiver.url(subscribed.name()));
	}

	@Test
	void aDueTestGoesBackToTheQueueAsATestEvent() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		UUID id = requested(subscribed);
		jdbc.update("UPDATE webhook_deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
		jdbc.update("DELETE FROM outbox_events WHERE tenant_id = ?", s.tenant());

		assertThat(scheduler.enqueueDue()).isGreaterThanOrEqualTo(1);

		assertThat(jdbc.queryForList("SELECT event_type, aggregate_id FROM outbox_events WHERE tenant_id = ?", s.tenant()))
			.containsExactly(Map.of("event_type", "WEBHOOK_TEST", "aggregate_id", subscribed.id()));
	}

}
