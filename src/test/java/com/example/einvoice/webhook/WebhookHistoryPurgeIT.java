package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** El historial de avisos no crece sin fin: lo terminado y antiguo se borra; lo pendiente, nunca. */
class WebhookHistoryPurgeIT extends AbstractWebhookIT {

	@Autowired
	WebhookHistoryPurge purge;

	@Autowired
	WebhookProperties settings;

	/** Un aviso de prueba de la suscripción, con ese estado y de hace tantos días. */
	UUID delivery(Subscribed subscribed, String status, int daysAgo) {
		return jdbc.queryForObject("""
				INSERT INTO webhook_deliveries (tenant_id, subscription_id, event_id, event_type, status, occurred_at)
				VALUES (?, ?, gen_random_uuid(), 'webhook.test', ?, now() - make_interval(days => ?)) RETURNING id
				""", UUID.class, s.tenant(), subscribed.id(), status, daysAgo);
	}

	boolean exists(UUID id) {
		return jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE id = ?", Integer.class, id) == 1;
	}

	@Test
	void finishedNotificationsOlderThanTheRetentionAreDeletedAndNothingElse() throws Exception {
		assertThat(settings.historyRetention().toDays()).isEqualTo(90);
		Subscribed subscribed = subscribe("document.authorized");
		UUID oldSent = delivery(subscribed, "SENT", 91);
		UUID oldFailed = delivery(subscribed, "FAILED", 91);
		UUID recentSent = delivery(subscribed, "SENT", 89);
		// Un aviso que sigue reintentándose no se toca, por antiguo que sea.
		UUID oldPending = delivery(subscribed, "PENDING", 200);
		UUID oldSending = delivery(subscribed, "SENDING", 200);

		assertThat(purge.purge()).isGreaterThanOrEqualTo(2);

		assertThat(exists(oldSent)).isFalse();
		assertThat(exists(oldFailed)).isFalse();
		assertThat(exists(recentSent)).isTrue();
		assertThat(exists(oldPending)).isTrue();
		assertThat(exists(oldSending)).isTrue();
	}

	@Test
	void aLargeBacklogIsDeletedInBatchesUntilNothingIsLeft() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		jdbc.update("""
				INSERT INTO webhook_deliveries (tenant_id, subscription_id, event_id, event_type, status, occurred_at)
				SELECT ?, ?, gen_random_uuid(), 'webhook.test', 'SENT', now() - interval '120 days'
				  FROM generate_series(1, 2500)
				""", s.tenant(), subscribed.id());

		assertThat(purge.purge()).isGreaterThanOrEqualTo(2500);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE subscription_id = ?", Integer.class,
				subscribed.id())).isZero();
	}

}
