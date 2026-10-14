package com.example.einvoice.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.einvoice.orchestration.DocumentEvent;
import com.example.einvoice.orchestration.MessagingConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * Consume la cola de avisos y envía los pendientes del comprobante. Recibir dos veces el mismo
 * evento es inocuo: cada aviso se reserva antes de enviarse.
 */
@Component
@ConditionalOnProperty(name = "einvoice.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookWorker {

	private static final Logger log = LoggerFactory.getLogger(WebhookWorker.class);

	private final WebhookDeliveryService webhooks;

	private final JsonMapper json;

	public WebhookWorker(WebhookDeliveryService webhooks, JsonMapper json) {
		this.webhooks = webhooks;
		this.json = json;
	}

	@RabbitListener(queues = MessagingConfig.WEBHOOK_QUEUE)
	public void handle(Message message) {
		DocumentEvent event = DocumentEvent.read(json, message);
		MDC.put("correlationId", String.valueOf(event.eventId()));
		try {
			if (DueWebhookScheduler.WEBHOOK_TEST.equals(event.eventType())) {
				// En un aviso de prueba, el agregado del evento es la suscripción.
				int sent = webhooks.processTests(event.tenantId(), event.documentId());
				log.info("Suscripción {}: {} aviso(s) de prueba entregado(s)", event.documentId(), sent);
				return;
			}
			int sent = webhooks.process(event.tenantId(), event.documentId());
			log.info("Documento {}: {} aviso(s) entregado(s) tras el evento {}", event.documentId(), sent,
					event.eventType());
		}
		finally {
			MDC.remove("correlationId");
		}
	}

}
