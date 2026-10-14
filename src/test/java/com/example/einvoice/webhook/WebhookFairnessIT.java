package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.WebhookReceiverStub.Reply;

/** Los avisos de una empresa no acaparan el envío: como mucho dos a la vez por empresa. */
class WebhookFairnessIT extends AbstractWebhookIT {

	@Autowired
	WebhookRepository repository;

	@Autowired
	WebhookProperties settings;

	/** Un aviso de prueba de la suscripción, en ese estado; si está en curso, desde hace tantos segundos. */
	UUID delivery(UUID tenant, UUID subscription, String status, long sendingForSeconds) {
		return jdbc.queryForObject("""
				INSERT INTO webhook_deliveries (tenant_id, subscription_id, event_id, event_type, status, sending_since,
				 attempt_count, next_attempt_at)
				VALUES (?, ?, gen_random_uuid(), 'webhook.test', ?,
				 CASE WHEN ? = 'SENDING' THEN now() - make_interval(secs => ?) END, 0, now())
				RETURNING id
				""", UUID.class, tenant, subscription, status, status, (double) sendingForSeconds);
	}

	java.util.Optional<WebhookRepository.Claim> claim(UUID id) {
		return repository.claim(id, settings.sendLease(), settings.maxConcurrentPerTenant(), settings.busyDelay());
	}

	Map<String, Object> row(UUID id) {
		return jdbc.queryForMap("SELECT * FROM webhook_deliveries WHERE id = ?", id);
	}

	int sending(UUID tenant) {
		return jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE tenant_id = ? AND status = 'SENDING'",
				Integer.class, tenant);
	}

	@Test
	void aCompanyAtItsLimitWaitsWithoutSpendingAnAttempt() throws Exception {
		assertThat(settings.maxConcurrentPerTenant()).isEqualTo(2);
		assertThat(settings.busyDelay()).isEqualTo(Duration.ofSeconds(5));
		Subscribed subscribed = subscribe("document.authorized");
		delivery(s.tenant(), subscribed.id(), "SENDING", 1);
		delivery(s.tenant(), subscribed.id(), "SENDING", 1);
		UUID waiting = delivery(s.tenant(), subscribed.id(), "PENDING", 0);

		assertThat(claim(waiting)).isEmpty();

		Map<String, Object> row = row(waiting);
		assertThat(row).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
		OffsetDateTime next = jdbc.queryForObject("SELECT next_attempt_at FROM webhook_deliveries WHERE id = ?",
				OffsetDateTime.class, waiting);
		OffsetDateTime now = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
		assertThat(Duration.between(now, next).toMillis()).isCloseTo(5000, within(1500L));
	}

	@Test
	void anotherCompanysSendsAndAbandonedSendsDoNotCount() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		BillingScenario other = newBillingScenario();
		UUID otherSubscription = jdbc.queryForObject("""
				INSERT INTO webhook_subscriptions (tenant_id, url, event_types, secret, created_by)
				SELECT ?, 'https://93.184.216.34/x', ARRAY['document.authorized'], '\\x00'::bytea, user_id
				  FROM memberships WHERE tenant_id = ? RETURNING id
				""", UUID.class, other.tenant(), other.tenant());
		delivery(other.tenant(), otherSubscription, "SENDING", 1);
		delivery(other.tenant(), otherSubscription, "SENDING", 1);
		// Dos envíos propios que llevan más que el plazo de envío: se dan por abandonados.
		long abandoned = settings.sendLease().toSeconds() + 5;
		delivery(s.tenant(), subscribed.id(), "SENDING", abandoned);
		delivery(s.tenant(), subscribed.id(), "SENDING", abandoned);
		UUID mine = delivery(s.tenant(), subscribed.id(), "PENDING", 0);

		assertThat(claim(mine)).isPresent();
		assertThat(row(mine)).containsEntry("status", "SENDING").containsEntry("attempt_count", 1);
	}

	@Test
	void concurrentClaimsOfOneCompanyNeverExceedTheLimit() throws Exception {
		Subscribed subscribed = subscribe("document.authorized");
		List<UUID> pending = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			pending.add(delivery(s.tenant(), subscribed.id(), "PENDING", 0));
		}
		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Boolean>> claims = new ArrayList<>();
			for (UUID id : pending) {
				claims.add(pool.submit(() -> {
					start.await();
					return claim(id).isPresent();
				}));
			}
			start.countDown();
			int claimed = 0;
			for (Future<Boolean> result : claims) {
				claimed += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
			}
			assertThat(claimed).isEqualTo(2);
			assertThat(sending(s.tenant())).isEqualTo(2);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aSlowReceiverDoesNotHoldBackAnotherCompany() throws Exception {
		Subscribed slow = subscribe("document.authorized");
		receiver.on(slow.name(), Reply.delayed(Duration.ofSeconds(3), 200));
		for (int i = 0; i < 10; i++) {
			delivery(s.tenant(), slow.id(), "PENDING", 0);
		}
		BillingScenario fastCompany = newBillingScenario();
		String name = "rapido-" + UUID.randomUUID();
		String body = "{\"url\":\"" + receiver.url(name) + "\",\"eventTypes\":[\"document.authorized\"]}";
		String created = mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/v1/webhooks/subscriptions"), fastCompany.owner(), body))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID fast = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.id"));
		UUID fastDelivery = delivery(fastCompany.tenant(), fast, "PENDING", 0);

		// Una cola con tres consumidores, como la de verdad pero más pequeña: tres mensajes de la empresa
		// lenta llegan antes que el de la rápida. Sin tope por empresa, los tres consumidores quedarían
		// enviando a la lenta y la rápida esperaría segundos; con él, el tercero la deja y atiende a la rápida.
		ExecutorService consumers = Executors.newFixedThreadPool(3);
		try {
			long started = System.nanoTime();
			for (int i = 0; i < 3; i++) {
				consumers.submit(() -> webhooks.processTests(s.tenant(), slow.id()));
			}
			Future<Integer> fastMessage = consumers.submit(() -> webhooks.processTests(fastCompany.tenant(), fast));

			assertThat(fastMessage.get(30, TimeUnit.SECONDS)).isEqualTo(1);
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
			assertThat(row(fastDelivery)).containsEntry("status", "SENT");
			assertThat(sending(s.tenant())).isLessThanOrEqualTo(2);
		}
		finally {
			consumers.shutdownNow();
			consumers.awaitTermination(30, TimeUnit.SECONDS);
		}
	}

}
