package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.WebhookReceiverStub.Reply;

class WebhookDeliveryApiIT extends AbstractWebhookIT {

	@Autowired
	WebhookProperties settings;

	ResultActions history(String token, UUID subscription, String query) throws Exception {
		return mvc.perform(as(get("/api/v1/webhooks/subscriptions/" + subscription + "/deliveries" + query), token));
	}

	ResultActions retry(String token, Object delivery) throws Exception {
		return mvc.perform(as(post("/api/v1/webhooks/deliveries/" + delivery + "/retry"), token));
	}

	/** Un aviso que agotó sus intentos. */
	UUID failedNotification(Subscribed subscribed) throws Exception {
		receiver.on(subscribed.name(), Reply.status(500));
		UUID document = invoiceIn("AUTHORIZED");
		jdbc.update("UPDATE webhook_deliveries SET attempt_count = ? WHERE document_id = ?", settings.retryMaxAttempts() - 1,
				document);
		webhooks.process(s.tenant(), document);
		assertThat(notification(document, "document.authorized")).containsEntry("status", "FAILED");
		return document;
	}

	@Test
	void theHistoryShowsWhatWasSentAndWhatFailedNewestFirst() throws Exception {
		Subscribed subscribed = subscribe("document.received", "document.authorized");
		Subscribed other = subscribe("document.authorized");
		UUID sent = invoiceIn("AUTHORIZED");
		webhooks.process(s.tenant(), sent);
		receiver.on(subscribed.name(), Reply.status(500));
		UUID failing = invoiceIn("AUTHORIZED");
		webhooks.process(s.tenant(), failing);

		history(s.owner(), subscribed.id(), "").andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.items", hasSize(4)))
			// Los dos del segundo comprobante, que fallaron, van primero.
			.andExpect(jsonPath("$.items[0].documentId").value(failing.toString()))
			.andExpect(jsonPath("$.items[0].status").value("PENDING"))
			.andExpect(jsonPath("$.items[0].attemptCount").value(1))
			.andExpect(jsonPath("$.items[0].lastStatus").value(500))
			.andExpect(jsonPath("$.items[0].lastError").isNotEmpty())
			.andExpect(jsonPath("$.items[0].nextAttemptAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].sentAt").value(nullValue()))
			.andExpect(jsonPath("$.items[3].documentId").value(sent.toString()))
			.andExpect(jsonPath("$.items[3].status").value("SENT"))
			.andExpect(jsonPath("$.items[3].lastStatus").value(200))
			.andExpect(jsonPath("$.items[3].sentAt").isNotEmpty())
			.andExpect(jsonPath("$.items[3].eventId").isNotEmpty())
			.andExpect(jsonPath("$.items[3].eventType").isNotEmpty());
		history(s.owner(), subscribed.id(), "?page=1&size=3").andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.total").value(4));
		history(s.owner(), other.id(), "").andExpect(jsonPath("$.total").value(2));
		history(s.owner(), subscribed.id(), "?size=1000").andExpect(status().isBadRequest());
	}

	@Test
	void aFailedNotificationCanBeSentAgain() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		UUID document = failedNotification(subscribed);
		Object id = notification(document, "document.authorized").get("id");
		receiver.on(subscribed.name(), Reply.status(200));

		retry(s.owner(), id).andExpect(status().isAccepted());

		assertThat(notification(document, "document.authorized")).containsEntry("status", "PENDING")
			.containsEntry("attempt_count", 0);
		assertThat(jdbc.queryForObject("SELECT next_attempt_at <= now() FROM webhook_deliveries WHERE id = ?", Boolean.class, id))
			.isTrue();
		assertThat(webhooks.process(s.tenant(), document)).isEqualTo(1);
		assertThat(notification(document, "document.authorized")).containsEntry("status", "SENT");
		// El reenvío lleva el identificador de siempre: el receptor puede reconocerlo.
		assertThat(receiver.received(subscribed.name())).extracting(request -> request.header("webhook-id")).hasSize(2)
			.containsOnly(notification(document, "document.authorized").get("event_id").toString());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE tenant_id = ? AND action = 'WEBHOOK_DELIVERY_RETRIED'", Integer.class,
				s.tenant()))
			.isEqualTo(1);
	}

	@Test
	void onlyAFailedNotificationOfAnActiveSubscriptionIsRetried() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		UUID sent = invoiceIn("AUTHORIZED");
		webhooks.process(s.tenant(), sent);
		UUID pending = invoiceIn("AUTHORIZED");

		for (UUID document : List.of(sent, pending)) {
			retry(s.owner(), notification(document, "document.authorized").get("id")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("WEBHOOK_DELIVERY_NOT_RETRYABLE"));
		}
		retry(s.owner(), UUID.randomUUID()).andExpect(status().isNotFound());

		UUID failed = failedNotification(subscribed);
		mvc.perform(json(put("/api/v1/webhooks/subscriptions/" + subscribed.id()), s.owner(),
				"{\"url\":\"" + receiver.url(subscribed.name()) + "\",\"eventTypes\":[\"document.authorized\"],\"enabled\":false}"))
			.andExpect(status().isOk());
		retry(s.owner(), notification(failed, "document.authorized").get("id")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("WEBHOOK_SUBSCRIPTION_DISABLED"));
		assertThat(notification(failed, "document.authorized")).containsEntry("status", "FAILED");
	}

	@Test
	void theHistoryAndTheRetryAreForOwnersAndAdministratorsOfThatCompany() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		Object id = notification(failedNotification(subscribed), "document.authorized").get("id");

		for (String role : List.of("BILLER", "AUDITOR")) {
			String token = memberToken(s.tenant(), role);
			history(token, subscribed.id(), "").andExpect(status().isForbidden());
			retry(token, id).andExpect(status().isForbidden());
		}
		BillingScenario stranger = newBillingScenario();
		history(stranger.owner(), subscribed.id(), "").andExpect(status().isNotFound());
		retry(stranger.owner(), id).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE id = ?", String.class, id))
			.isEqualTo("FAILED");

		history(memberToken(s.tenant(), "ADMIN"), subscribed.id(), "").andExpect(status().isOk());
		retry(memberToken(s.tenant(), "ADMIN"), id).andExpect(status().isAccepted());
	}

}
