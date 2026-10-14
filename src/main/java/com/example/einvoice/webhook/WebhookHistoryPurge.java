package com.example.einvoice.webhook;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Borra del historial los avisos ya entregados o fallidos más antiguos que la retención. Los
 * pendientes y en curso no se tocan, por antiguos que sean: siguen reintentándose. Borrar no deja
 * auditoría: el historial de avisos es operativo, no fiscal.
 */
@Component
public class WebhookHistoryPurge {

	static final int BATCH = 1000;

	private final JdbcClient jdbc;

	private final WebhookProperties properties;

	public WebhookHistoryPurge(JdbcClient jdbc, WebhookProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	/** Borra por lotes hasta que no quede nada vencido y devuelve cuántos avisos borró. */
	public int purge() {
		int total = 0;
		int deleted;
		do {
			// Lotes cortos, cada uno en su propia sentencia: no retiene bloqueos sobre la tabla mucho rato.
			deleted = jdbc.sql("""
					DELETE FROM webhook_deliveries WHERE id IN (
					 SELECT id FROM webhook_deliveries
					  WHERE status IN ('SENT', 'FAILED')
					    AND occurred_at < now() - make_interval(secs => CAST(:retention AS double precision))
					  LIMIT :batch)
					""")
				.param("retention", (double) properties.historyRetention().toSeconds())
				.param("batch", BATCH)
				.update();
			total += deleted;
		}
		while (deleted == BATCH);
		return total;
	}

}
