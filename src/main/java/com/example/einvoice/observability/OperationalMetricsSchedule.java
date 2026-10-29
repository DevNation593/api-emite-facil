package com.example.einvoice.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recalcula las métricas de operación solo si alguien puede leerlas: sin la ficha de métricas no
 * existen, y cada instancia de la API consultaría la base y el broker cada 30 s para nada.
 */
@Component
class OperationalMetricsSchedule {

	private final OperationalMetrics metrics;

	private final boolean exported;

	OperationalMetricsSchedule(OperationalMetrics metrics, @Value("${einvoice.metrics.token:}") String token) {
		this.metrics = metrics;
		this.exported = !token.isBlank();
	}

	@Scheduled(fixedDelayString = "${einvoice.metrics.refresh-interval-ms:30000}")
	void refresh() {
		if (exported) {
			metrics.refresh();
		}
	}

}
