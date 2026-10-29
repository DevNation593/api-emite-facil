package com.example.einvoice.observability;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.example.einvoice.orchestration.MessagingConfig;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Estado de la operación como métricas: qué espera, desde cuándo y qué se acumula. Los valores se
 * recalculan cada cierto tiempo con unas pocas consultas, no en cada lectura, y sus etiquetas solo
 * nombran estados y colas: nunca empresas, personas ni comprobantes.
 */
@Component
public class OperationalMetrics {

	private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);

	/** Estados en los que un comprobante espera algo. Los autorizados no se cuentan. */
	static final List<String> WAITING_STATES = List.of("RESERVED", "GENERATED", "SIGNED", "SENDING", "RECEIVED");

	/** Estados en los que un comprobante necesita que alguien intervenga. */
	static final List<String> STOPPED_STATES = List.of("XSD_INVALID", "SIGNATURE_FAILED", "RETURNED", "NOT_AUTHORIZED");

	static final List<String> OPEN_DELIVERIES = List.of("PENDING", "SENDING", "FAILED");

	static final List<String> OPEN_WEBHOOKS = List.of("PENDING", "SENDING", "FAILED");

	static final List<String> QUEUES = List.of(MessagingConfig.ISSUE_QUEUE, MessagingConfig.DEAD_LETTER_QUEUE,
			MessagingConfig.DELIVER_QUEUE, MessagingConfig.DELIVER_DEAD_LETTER_QUEUE, MessagingConfig.WEBHOOK_QUEUE,
			MessagingConfig.WEBHOOK_DEAD_LETTER_QUEUE);

	private record StateRow(String state, long total, double oldest) {
	}

	private record StatusRow(String status, long total) {
	}

	private record OutboxRow(long total, double oldest) {
	}

	private final JdbcClient jdbc;

	private final AmqpAdmin amqp;

	/** Última lectura. Una clave sin valor vale cero. */
	private volatile Map<String, Double> values = Map.of();

	public OperationalMetrics(JdbcClient jdbc, AmqpAdmin amqp, MeterRegistry registry) {
		this.jdbc = jdbc;
		this.amqp = amqp;
		for (String state : WAITING_STATES) {
			gauge(registry, "einvoice.documents", "documents." + state, "Comprobantes por estado, sin los autorizados",
					"state", state);
			gauge(registry, "einvoice.documents.oldest.seconds", "oldest." + state,
					"Cuánto lleva en ese estado el comprobante que más tiempo lleva en él", "state", state);
		}
		for (String state : STOPPED_STATES) {
			gauge(registry, "einvoice.documents", "documents." + state, "Comprobantes por estado, sin los autorizados",
					"state", state);
		}
		for (String status : OPEN_DELIVERIES) {
			gauge(registry, "einvoice.deliveries", "deliveries." + status, "Entregas por estado, sin las enviadas",
					"status", status);
		}
		for (String status : OPEN_WEBHOOKS) {
			gauge(registry, "einvoice.webhooks", "webhooks." + status,
					"Avisos a sistemas de las empresas por estado, sin los entregados", "status", status);
		}
		gauge(registry, "einvoice.webhooks.disabled", "webhooks.disabled",
				"Suscripciones que se apagaron solas por fallar demasiadas veces seguidas", null, null);
		for (String queue : QUEUES) {
			gauge(registry, "einvoice.queue.messages", "queue." + queue, "Mensajes en la cola", "queue", queue);
		}
		gauge(registry, "einvoice.outbox.pending", "outbox.pending", "Eventos del outbox aún sin publicar", null, null);
		gauge(registry, "einvoice.outbox.oldest.seconds", "outbox.oldest",
				"Antigüedad del evento más antiguo sin publicar", null, null);
		gauge(registry, "einvoice.certificates.expiring", "certificates.expiring",
				"Certificados activos caducados o que caducan en menos de 30 días", null, null);
		gauge(registry, "einvoice.artifacts.corrupt", "artifacts.corrupt",
				"Artefactos cuyo contenido ya no corresponde a su huella, según la última verificación", null, null);
	}

	private void gauge(MeterRegistry registry, String name, String key, String description, String tag, String value) {
		Gauge.Builder<OperationalMetrics> builder = Gauge
			.builder(name, this, metrics -> metrics.values.getOrDefault(key, 0d))
			.description(description);
		if (tag != null) {
			builder.tag(tag, value);
		}
		builder.register(registry);
	}

	/**
	 * Vuelve a leer el estado. Si la base o el broker no responden, se conserva la lectura anterior.
	 * Lo llama {@link OperationalMetricsSchedule}.
	 */
	public void refresh() {
		Map<String, Double> next = new HashMap<>(values);
		try {
			// Solo una lectura completa sustituye a la anterior: a medias, publicaría ceros.
			next.putAll(readDatabase());
		}
		catch (RuntimeException ex) {
			log.warn("No se pudieron leer las métricas de la base: {}", ex.toString());
		}
		try {
			for (String queue : QUEUES) {
				QueueInformation information = amqp.getQueueInfo(queue);
				next.put("queue." + queue, information == null ? 0d : information.getMessageCount());
			}
		}
		catch (RuntimeException ex) {
			log.warn("No se pudo leer el tamaño de las colas: {}", ex.toString());
		}
		values = Map.copyOf(next);
	}

	private Map<String, Double> readDatabase() {
		Map<String, Double> next = new HashMap<>();
		WAITING_STATES.forEach(state -> {
			next.put("documents." + state, 0d);
			next.put("oldest." + state, 0d);
		});
		STOPPED_STATES.forEach(state -> next.put("documents." + state, 0d));
		OPEN_DELIVERIES.forEach(status -> next.put("deliveries." + status, 0d));
		OPEN_WEBHOOKS.forEach(status -> next.put("webhooks." + status, 0d));
		jdbc.sql("""
				SELECT state, count(*) AS total, extract(epoch FROM now() - min(state_changed_at)) AS oldest
				  FROM fiscal_documents WHERE state <> 'AUTHORIZED' GROUP BY state
				""").query(StateRow.class).list().forEach(row -> {
			next.put("documents." + row.state(), (double) row.total());
			next.put("oldest." + row.state(), row.oldest());
		});
		jdbc.sql("SELECT status, count(*) AS total FROM deliveries WHERE status <> 'SENT' GROUP BY status")
			.query(StatusRow.class)
			.list()
			.forEach(row -> next.put("deliveries." + row.status(), (double) row.total()));
		jdbc.sql("SELECT status, count(*) AS total FROM webhook_deliveries WHERE status <> 'SENT' GROUP BY status")
			.query(StatusRow.class)
			.list()
			.forEach(row -> next.put("webhooks." + row.status(), (double) row.total()));
		next.put("webhooks.disabled",
				jdbc.sql("SELECT count(*) FROM webhook_subscriptions WHERE disabled_reason = 'TOO_MANY_FAILURES'")
					.query(Double.class)
					.single());
		OutboxRow outbox = jdbc.sql("""
				SELECT count(*) AS total, coalesce(extract(epoch FROM now() - min(created_at)), 0) AS oldest
				  FROM outbox_events WHERE dispatched_at IS NULL
				""").query(OutboxRow.class).single();
		next.put("outbox.pending", (double) outbox.total());
		next.put("outbox.oldest", outbox.oldest());
		next.put("certificates.expiring",
				jdbc.sql("SELECT count(*) FROM certificates WHERE status = 'ACTIVE' AND valid_to < now() + interval '30 days'")
					.query(Double.class)
					.single());
		next.put("artifacts.corrupt",
				jdbc.sql("SELECT count(*) FROM artifact_integrity WHERE NOT ok").query(Double.class).single());
		return next;
	}

}
