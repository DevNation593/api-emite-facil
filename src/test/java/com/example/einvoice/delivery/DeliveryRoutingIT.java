package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.orchestration.MessagingConfig;
import com.example.einvoice.orchestration.OutboxRelay;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.rabbitmq.client.GetResponse;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Cada tipo de evento del outbox va a su cola. En este contexto el worker está apagado: nadie consume. */
class DeliveryRoutingIT extends AbstractIntegrationTest {

	@Autowired
	OutboxRelay relay;

	@Autowired
	RabbitTemplate rabbit;

	@Autowired
	JsonMapper json;

	UUID tenant;

	@BeforeEach
	void emptyQueues() throws Exception {
		tenant = createTenant(newUserToken());
		while (relay.publishPending() > 0) {
			// Lo que dejaron otras pruebas se publica antes de vaciar las colas.
		}
		for (String queue : new String[] { MessagingConfig.ISSUE_QUEUE, MessagingConfig.DELIVER_QUEUE,
				MessagingConfig.DEAD_LETTER_QUEUE, MessagingConfig.DELIVER_DEAD_LETTER_QUEUE }) {
			drain(queue);
		}
	}

	UUID event(String type) {
		UUID aggregate = UUID.randomUUID();
		jdbc.update("INSERT INTO outbox_events (tenant_id, aggregate_id, event_type, payload)"
				+ " VALUES (?, ?, ?, '{}'::jsonb)", tenant, aggregate, type);
		return aggregate;
	}

	/** Vacía la cola y devuelve los documentos de los mensajes que tenía. */
	List<String> drain(String queue) {
		List<String> documents = new ArrayList<>();
		Message message;
		while ((message = rabbit.receive(queue, 300)) != null) {
			Map<String, String> body = json.readValue(message.getBody(), new TypeReference<Map<String, String>>() {
			});
			documents.add(body.get("documentId"));
		}
		return documents;
	}

	@Test
	void eventsAreRoutedByType() {
		UUID authorized = event("DOCUMENT_AUTHORIZED");
		UUID due = event("DELIVERY_DUE");
		UUID requested = event("DELIVERY_REQUESTED");
		UUID reserved = event("INVOICE_RESERVED");
		UUID documentDue = event("DOCUMENT_DUE");
		UUID retry = event("DOCUMENT_RETRY_REQUESTED");

		while (relay.publishPending() > 0) {
			// Publica todo.
		}

		assertThat(drain(MessagingConfig.DELIVER_QUEUE)).containsExactlyInAnyOrder(authorized.toString(),
				due.toString(), requested.toString());
		assertThat(drain(MessagingConfig.ISSUE_QUEUE)).containsExactlyInAnyOrder(reserved.toString(),
				documentDue.toString(), retry.toString());
	}

	@Test
	void rejectedDeliveryMessageGoesToItsDeadLetterQueue() {
		UUID authorized = event("DOCUMENT_AUTHORIZED");
		while (relay.publishPending() > 0) {
			// Publica todo.
		}

		rabbit.execute(channel -> {
			GetResponse delivery = channel.basicGet(MessagingConfig.DELIVER_QUEUE, false);
			channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
			return null;
		});

		assertThat(drain(MessagingConfig.DELIVER_DEAD_LETTER_QUEUE)).containsExactly(authorized.toString());
		assertThat(drain(MessagingConfig.DEAD_LETTER_QUEUE)).isEmpty();
		assertThat(drain(MessagingConfig.DELIVER_QUEUE)).isEmpty();
	}

}
