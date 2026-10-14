package com.example.einvoice.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purga el historial de avisos en el worker, cada hora. */
@Component
@ConditionalOnProperty(name = "einvoice.worker.enabled", havingValue = "true", matchIfMissing = true)
class WebhookPurgeSchedule {

	private static final Logger log = LoggerFactory.getLogger(WebhookPurgeSchedule.class);

	private final WebhookHistoryPurge purge;

	WebhookPurgeSchedule(WebhookHistoryPurge purge) {
		this.purge = purge;
	}

	@Scheduled(initialDelayString = "${einvoice.webhooks.purge-interval-ms:3600000}",
			fixedDelayString = "${einvoice.webhooks.purge-interval-ms:3600000}")
	void purge() {
		try {
			int deleted = purge.purge();
			if (deleted > 0) {
				log.info("Historial de avisos: {} avisos antiguos borrados", deleted);
			}
		}
		catch (RuntimeException ex) {
			// Base no disponible: la siguiente pasada lo vuelve a intentar.
			log.warn("La purga del historial de avisos falló: {}", ex.toString());
		}
	}

}
