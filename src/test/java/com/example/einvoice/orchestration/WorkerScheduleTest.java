package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.einvoice.delivery.DueDeliveryScheduler;
import com.example.einvoice.webhook.DueWebhookScheduler;

class WorkerScheduleTest {

	@Test
	void aFailureWithTheNotificationsDoesNotStopTheDocuments() {
		AtomicInteger documents = new AtomicInteger();
		AtomicInteger deliveries = new AtomicInteger();
		AtomicInteger published = new AtomicInteger();
		DueDocumentScheduler due = new DueDocumentScheduler(null, null, Duration.ofMinutes(5)) {
			@Override
			public int enqueueDue() {
				return documents.incrementAndGet();
			}
		};
		DueDeliveryScheduler dueDeliveries = new DueDeliveryScheduler(null, null, Duration.ofMinutes(5)) {
			@Override
			public int enqueueDue() {
				return deliveries.incrementAndGet();
			}
		};
		DueWebhookScheduler dueWebhooks = new DueWebhookScheduler(null, null, null, Duration.ofMinutes(5)) {
			@Override
			public int enqueueDue() {
				throw new IllegalStateException("la tabla de avisos no responde");
			}
		};
		OutboxRelay relay = new OutboxRelay(null, null, null) {
			@Override
			public int publishPending() {
				return published.getAndIncrement() == 0 ? 3 : 0;
			}
		};

		new WorkerSchedule(due, dueDeliveries, dueWebhooks, relay).tick();

		// Los avisos son un añadido: si fallan, el comprobante sigue su camino ante el SRI.
		assertThat(documents).hasValue(1);
		assertThat(deliveries).hasValue(1);
		assertThat(published).hasValue(2);
	}

}
