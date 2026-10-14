package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.rabbitmq.client.GetResponse;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Publicación del outbox en RabbitMQ. En este contexto el worker está apagado: nadie consume la cola. */
class OutboxRelayIT extends AbstractIntegrationTest {

	@Autowired
	OutboxRelay relay;

	@Autowired
	RabbitTemplate rabbit;

	@Autowired
	JsonMapper json;

	BillingScenario s;

	UUID document;

	@BeforeEach
	void issuedInvoice() throws Exception {
		drain(MessagingConfig.ISSUE_QUEUE);
		drain(MessagingConfig.DEAD_LETTER_QUEUE);
		s = newBillingScenario();
		document = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
	}

	List<Map<String, String>> drain(String queue) {
		List<Map<String, String>> bodies = new ArrayList<>();
		Message message;
		while ((message = rabbit.receive(queue, 300)) != null) {
			try {
				bodies.add(json.readValue(message.getBody(), new TypeReference<Map<String, String>>() {
				}));
			}
			catch (RuntimeException notJson) {
				bodies.add(Map.of("raw", new String(message.getBody(), StandardCharsets.UTF_8)));
			}
		}
		return bodies;
	}

	void publishEverything() {
		while (relay.publishPending() > 0) {
			// Otras pruebas dejaron eventos pendientes: se publican todos.
		}
	}

	@Test
	void pendingEventsArePublishedOnceAndMarked() {
		publishEverything();

		List<Map<String, String>> mine = drain(MessagingConfig.ISSUE_QUEUE).stream()
			.filter(body -> document.toString().equals(body.get("documentId")))
			.toList();
		assertThat(mine).hasSize(1);
		assertThat(mine.get(0)).containsEntry("tenantId", s.tenant().toString())
			.containsEntry("eventType", "INVOICE_RESERVED");
		assertThat(jdbc.queryForObject("SELECT id::text FROM outbox_events WHERE aggregate_id = ?", String.class,
				document))
			.isEqualTo(mine.get(0).get("eventId"));
		assertThat(jdbc.queryForObject("SELECT dispatched_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?",
				Boolean.class, document))
			.isTrue();

		assertThat(relay.publishPending()).isZero();
		assertThat(drain(MessagingConfig.ISSUE_QUEUE)).isEmpty();
	}

	@Test
	void messageCarriesOnlyIdentifiers() {
		publishEverything();

		Map<String, String> mine = drain(MessagingConfig.ISSUE_QUEUE).stream()
			.filter(body -> document.toString().equals(body.get("documentId")))
			.findFirst()
			.orElseThrow();
		assertThat(mine).containsOnlyKeys("eventId", "tenantId", "documentId", "eventType");
	}

	@Test
	void rejectedMessageGoesToTheDeadLetterQueue() {
		publishEverything();

		int rejected = rabbit.execute(channel -> {
			int count = 0;
			GetResponse delivery;
			while ((delivery = channel.basicGet(MessagingConfig.ISSUE_QUEUE, false)) != null) {
				channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
				count++;
			}
			return count;
		});

		assertThat(rejected).isGreaterThanOrEqualTo(1);
		List<Map<String, String>> dead = drain(MessagingConfig.DEAD_LETTER_QUEUE);
		assertThat(dead).hasSize(rejected);
		assertThat(dead).anyMatch(body -> document.toString().equals(body.get("documentId")));
		assertThat(drain(MessagingConfig.ISSUE_QUEUE)).isEmpty();
	}

}
