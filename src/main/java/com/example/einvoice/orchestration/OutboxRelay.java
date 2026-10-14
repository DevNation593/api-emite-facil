package com.example.einvoice.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Publica en RabbitMQ los eventos pendientes del outbox. Un evento se marca como despachado solo
 * después de que el broker confirma haberlo recibido; si algo falla se publica otra vez más tarde,
 * así que un mismo evento puede llegar repetido y quien lo consume debe tolerarlo.
 */
@Component
public class OutboxRelay {

	private static final int BATCH = 50;

	private static final long CONFIRM_TIMEOUT_MS = 10_000;

	private record PendingEvent(UUID id, UUID tenantId, UUID aggregateId, String eventType) {
	}

	private final JdbcClient jdbc;

	private final RabbitTemplate rabbit;

	private final JsonMapper json;

	public OutboxRelay(JdbcClient jdbc, RabbitTemplate rabbit, JsonMapper json) {
		this.jdbc = jdbc;
		this.rabbit = rabbit;
		this.json = json;
	}

	/** Publica un lote de eventos pendientes y devuelve cuántos fueron. */
	@Transactional
	public int publishPending() {
		// SKIP LOCKED: varias instancias pueden publicar a la vez sin estorbarse ni repetir el lote.
		List<PendingEvent> events = jdbc.sql("""
				SELECT id, tenant_id, aggregate_id, event_type FROM outbox_events
				WHERE dispatched_at IS NULL ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED
				""").param("batch", BATCH).query(PendingEvent.class).list();
		if (events.isEmpty()) {
			return 0;
		}
		rabbit.invoke(operations -> {
			for (PendingEvent event : events) {
				operations.send(MessagingConfig.EVENTS_EXCHANGE, MessagingConfig.routingKey(event.eventType()),
						message(event));
			}
			operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
			return null;
		});
		jdbc.sql("UPDATE outbox_events SET dispatched_at = now() WHERE id IN (:ids)")
			.param("ids", events.stream().map(PendingEvent::id).toList())
			.update();
		return events.size();
	}

	/** El mensaje lleva solo identificadores: nunca XML, datos del cliente ni certificados. */
	private Message message(PendingEvent event) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("eventId", event.id().toString());
		body.put("tenantId", event.tenantId().toString());
		body.put("documentId", event.aggregateId().toString());
		body.put("eventType", event.eventType());
		return MessageBuilder.withBody(json.writeValueAsBytes(body))
			.setContentType("application/json")
			.setMessageId(event.id().toString())
			.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
			.build();
	}

}
