package com.example.einvoice.orchestration;

import java.util.UUID;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Evento del outbox tal como viaja por RabbitMQ: solo identificadores. */
public record DocumentEvent(UUID eventId, UUID tenantId, UUID documentId, String eventType) {

	/** Lee el mensaje; uno ilegible o incompleto se rechaza sin volver a la cola. */
	public static DocumentEvent read(JsonMapper json, Message message) {
		try {
			DocumentEvent event = json.readValue(message.getBody(), DocumentEvent.class);
			if (event.tenantId() == null || event.documentId() == null) {
				throw new AmqpRejectAndDontRequeueException("Evento sin empresa o sin documento");
			}
			return event;
		}
		catch (JacksonException ex) {
			throw new AmqpRejectAndDontRequeueException("Evento ilegible", ex);
		}
	}

}
