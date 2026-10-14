package com.example.einvoice.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recorre el archivo por lotes en el worker: con el tiempo, todo artefacto vuelve a verificarse. */
@Component
@ConditionalOnProperty(name = "einvoice.worker.enabled", havingValue = "true", matchIfMissing = true)
class IntegritySchedule {

	private static final Logger log = LoggerFactory.getLogger(IntegritySchedule.class);

	private final ArtifactIntegrityVerifier verifier;

	private final int batchSize;

	IntegritySchedule(ArtifactIntegrityVerifier verifier, @Value("${einvoice.integrity.batch-size:2000}") int batchSize) {
		this.verifier = verifier;
		this.batchSize = batchSize;
	}

	@Scheduled(initialDelayString = "${einvoice.integrity.initial-delay-ms:60000}",
			fixedDelayString = "${einvoice.integrity.interval-ms:900000}")
	void verify() {
		try {
			ArtifactIntegrityVerifier.Result result = verifier.verifyNext(batchSize);
			if (result.corrupt() > 0) {
				log.error("Verificación de integridad: {} artefactos alterados de {} revisados", result.corrupt(),
						result.checked());
			}
		}
		catch (RuntimeException ex) {
			// Base no disponible: la siguiente pasada lo vuelve a intentar.
			log.warn("La verificación de integridad falló: {}", ex.toString());
		}
	}

}
