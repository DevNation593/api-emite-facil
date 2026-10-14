package com.example.einvoice.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.einvoice.delivery.DueDeliveryScheduler;
import com.example.einvoice.webhook.DueWebhookScheduler;

/** Latido del worker: encola los comprobantes, las entregas y los avisos vencidos y publica el outbox. */
@Component
@ConditionalOnProperty(name = "einvoice.worker.enabled", havingValue = "true", matchIfMissing = true)
class WorkerSchedule {

	private static final Logger log = LoggerFactory.getLogger(WorkerSchedule.class);

	/** Tope de lotes por latido, para que una avalancha de eventos no acapare el hilo del planificador. */
	private static final int MAX_BATCHES_PER_TICK = 20;

	private final DueDocumentScheduler due;

	private final DueDeliveryScheduler dueDeliveries;

	private final DueWebhookScheduler dueWebhooks;

	private final OutboxRelay relay;

	WorkerSchedule(DueDocumentScheduler due, DueDeliveryScheduler dueDeliveries, DueWebhookScheduler dueWebhooks,
			OutboxRelay relay) {
		this.due = due;
		this.dueDeliveries = dueDeliveries;
		this.dueWebhooks = dueWebhooks;
		this.relay = relay;
	}

	/** Los avisos son un añadido: si su planificador falla, el resto del latido sigue. */
	private void enqueueDueWebhooks() {
		try {
			dueWebhooks.enqueueDue();
		}
		catch (RuntimeException ex) {
			log.warn("No se pudieron encolar los avisos vencidos: {}", ex.toString());
		}
	}

	@Scheduled(fixedDelayString = "${einvoice.worker.poll-interval-ms:1000}")
	void tick() {
		try {
			due.enqueueDue();
			dueDeliveries.enqueueDue();
			enqueueDueWebhooks();
			int batches = 0;
			while (relay.publishPending() > 0 && ++batches < MAX_BATCHES_PER_TICK) {
				// Sigue mientras queden eventos por publicar.
			}
		}
		catch (RuntimeException ex) {
			// Base o broker no disponibles: nada se pierde, el siguiente latido lo vuelve a intentar.
			log.warn("El latido del worker falló: {}", ex.toString());
		}
	}

}
