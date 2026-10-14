package com.example.einvoice.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.einvoice.orchestration.DocumentEvent;
import com.example.einvoice.orchestration.MessagingConfig;
import com.example.einvoice.web.ApiException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Consume la cola de entrega: archiva el comprobante autorizado y envía sus entregas pendientes.
 * Recibir dos veces el mismo evento es inocuo: cada entrega se reserva antes de enviarse.
 */
@Component
@ConditionalOnProperty(name = "einvoice.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryWorker {

	private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

	private final DeliveryService deliveries;

	private final JsonMapper json;

	public DeliveryWorker(DeliveryService deliveries, JsonMapper json) {
		this.deliveries = deliveries;
		this.json = json;
	}

	@RabbitListener(queues = MessagingConfig.DELIVER_QUEUE)
	public void handle(Message message) {
		DocumentEvent event = DocumentEvent.read(json, message);
		MDC.put("correlationId", String.valueOf(event.eventId()));
		try {
			int sent = deliveries.process(event.tenantId(), event.documentId());
			log.info("Documento {}: {} correo(s) enviado(s) tras el evento {}", event.documentId(), sent,
					event.eventType());
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
