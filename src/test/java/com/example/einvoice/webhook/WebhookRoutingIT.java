package com.example.einvoice.webhook;

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

/** Los avisos tienen su propia cola: un receptor lento no retrasa al SRI ni al correo. Aquí nadie consume. */
class WebhookRoutingIT extends AbstractIntegrationTest {

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
				MessagingConfig.WEBHOOK_QUEUE, MessagingConfig.WEBHOOK_DEAD_LETTER_QUEUE }) {
			drain(queue);
		}
	}

	UUID event(String type) {
		UUID aggregate = UUID.randomUUID();
		jdbc.update("INSERT INTO outbox_events (tenant_id, aggregate_id, event_type, payload)"
				+ " VALUES (?, ?, ?, '{}'::jsonb)", tenant, aggregate, type);
		return aggregate;
	}

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
	void dueNotificationsGoToTheirOwnQueue() {
		UUID due = event("WEBHOOK_DUE");
		UUID authorized = event("DOCUMENT_AUTHORIZED");
		UUID reserved = event("INVOICE_RESERVED");
		while (relay.publishPending() > 0) {
			// Publica todo.
		}

		assertThat(drain(MessagingConfig.WEBHOOK_QUEUE)).containsExactly(due.toString());
		assertThat(drain(MessagingConfig.DELIVER_QUEUE)).containsExactly(authorized.toString());
		assertThat(drain(MessagingConfig.ISSUE_QUEUE)).containsExactly(reserved.toString());
	}

	@Test
	void aRejectedMessageGoesToItsOwnDeadLetterQueue() {
		UUID due = event("WEBHOOK_DUE");
		while (relay.publishPending() > 0) {
			// Publica todo.
		}

		rabbit.execute(channel -> {
			GetResponse delivery = channel.basicGet(MessagingConfig.WEBHOOK_QUEUE, false);
			channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
			return null;
		});

		assertThat(drain(MessagingConfig.WEBHOOK_DEAD_LETTER_QUEUE)).containsExactly(due.toString());
		assertThat(drain(MessagingConfig.WEBHOOK_QUEUE)).isEmpty();
	}

}
