package com.example.einvoice.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.einvoice.web.ApiException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Consume la cola de comprobantes y hace avanzar cada documento. Recibir dos veces el mismo evento
 * es inocuo: el pipeline decide qué hacer según el estado en que encuentre el documento.
 */
@Component
@ConditionalOnProperty(name = "einvoice.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentWorker {

	private static final Logger log = LoggerFactory.getLogger(DocumentWorker.class);

	private final DocumentPipeline pipeline;

	private final JsonMapper json;

	public DocumentWorker(DocumentPipeline pipeline, JsonMapper json) {
		this.pipeline = pipeline;
		this.json = json;
	}

	@RabbitListener(queues = MessagingConfig.ISSUE_QUEUE)
	public void handle(Message message) {
		DocumentEvent event = DocumentEvent.read(json, message);
		MDC.put("correlationId", String.valueOf(event.eventId()));
		try {
			String state = pipeline.advance(event.tenantId(), event.documentId());
			log.info("Documento {} en estado {} tras el evento {}", event.documentId(), state, event.eventType());
		}
		catch (ApiException ex) {
			// El documento no existe para esa empresa: reintentar no lo arreglará.
			log.warn("Evento {} descartado: el documento {} no existe", event.eventId(), event.documentId());
		}
		finally {
			MDC.remove("correlationId");
		}
	}

}
