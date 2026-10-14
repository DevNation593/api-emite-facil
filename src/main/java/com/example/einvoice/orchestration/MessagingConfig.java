package com.example.einvoice.orchestration;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Topología de RabbitMQ. Los eventos del outbox llegan a la cola de procesamiento de comprobantes;
 * un mensaje que el worker rechaza definitivamente pasa a la cola de mensajes muertos, donde espera
 * a un operador.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MessagingConfig {

	public static final String EVENTS_EXCHANGE = "fiscal.events";

	public static final String ISSUE_QUEUE = "document.issue";

	public static final String ISSUE_ROUTING_KEY = "document.issue";

	/** Cola de archivo y entrega de comprobantes autorizados. */
	public static final String DELIVER_QUEUE = "document.deliver";

	public static final String DELIVER_ROUTING_KEY = "document.deliver";

	public static final String DELIVER_DEAD_LETTER_QUEUE = "document.deliver.dlq";

	/** Cola de avisos a los sistemas de las empresas: aparte, para que un receptor lento no frene lo demás. */
	public static final String WEBHOOK_QUEUE = "webhook.deliver";

	public static final String WEBHOOK_ROUTING_KEY = "webhook.deliver";

	public static final String WEBHOOK_DEAD_LETTER_QUEUE = "webhook.deliver.dlq";

	public static final String DEAD_LETTER_EXCHANGE = "fiscal.dlx";

	public static final String DEAD_LETTER_QUEUE = "document.issue.dlq";

	@Bean
	TopicExchange fiscalEvents() {
		return new TopicExchange(EVENTS_EXCHANGE, true, false);
	}

	@Bean
	DirectExchange fiscalDeadLetters() {
		return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
	}

	@Bean
	Queue documentIssueQueue() {
		return QueueBuilder.durable(ISSUE_QUEUE)
			.deadLetterExchange(DEAD_LETTER_EXCHANGE)
			.deadLetterRoutingKey(DEAD_LETTER_QUEUE)
			.build();
	}

	@Bean
	Queue documentIssueDeadLetterQueue() {
		return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
	}

	@Bean
	Queue documentDeliverQueue() {
		return QueueBuilder.durable(DELIVER_QUEUE)
			.deadLetterExchange(DEAD_LETTER_EXCHANGE)
			.deadLetterRoutingKey(DELIVER_DEAD_LETTER_QUEUE)
			.build();
	}

	@Bean
	Queue documentDeliverDeadLetterQueue() {
		return QueueBuilder.durable(DELIVER_DEAD_LETTER_QUEUE).build();
	}

	@Bean
	Binding documentDeliverBinding(Queue documentDeliverQueue, TopicExchange fiscalEvents) {
		return BindingBuilder.bind(documentDeliverQueue).to(fiscalEvents).with(DELIVER_ROUTING_KEY);
	}

	@Bean
	Binding documentDeliverDeadLetterBinding(Queue documentDeliverDeadLetterQueue,
			DirectExchange fiscalDeadLetters) {
		return BindingBuilder.bind(documentDeliverDeadLetterQueue)
			.to(fiscalDeadLetters)
			.with(DELIVER_DEAD_LETTER_QUEUE);
	}

	@Bean
	Queue webhookDeliverQueue() {
		return QueueBuilder.durable(WEBHOOK_QUEUE)
			.deadLetterExchange(DEAD_LETTER_EXCHANGE)
			.deadLetterRoutingKey(WEBHOOK_DEAD_LETTER_QUEUE)
			.build();
	}

	@Bean
	Queue webhookDeliverDeadLetterQueue() {
		return QueueBuilder.durable(WEBHOOK_DEAD_LETTER_QUEUE).build();
	}

	@Bean
	Binding webhookDeliverBinding(Queue webhookDeliverQueue, TopicExchange fiscalEvents) {
		return BindingBuilder.bind(webhookDeliverQueue).to(fiscalEvents).with(WEBHOOK_ROUTING_KEY);
	}

	@Bean
	Binding webhookDeliverDeadLetterBinding(Queue webhookDeliverDeadLetterQueue, DirectExchange fiscalDeadLetters) {
		return BindingBuilder.bind(webhookDeliverDeadLetterQueue).to(fiscalDeadLetters).with(WEBHOOK_DEAD_LETTER_QUEUE);
	}

	/**
	 * Cola a la que va cada tipo de evento del outbox: la autorización y todo lo relativo a la
	 * entrega, a la de entrega; los avisos, a la suya; el resto hace avanzar el comprobante ante el SRI.
	 */
	public static String routingKey(String eventType) {
		if (eventType.startsWith("WEBHOOK_")) {
			return WEBHOOK_ROUTING_KEY;
		}
		return "DOCUMENT_AUTHORIZED".equals(eventType) || eventType.startsWith("DELIVERY_") ? DELIVER_ROUTING_KEY
				: ISSUE_ROUTING_KEY;
	}

	@Bean
	Binding documentIssueBinding(Queue documentIssueQueue, TopicExchange fiscalEvents) {
		return BindingBuilder.bind(documentIssueQueue).to(fiscalEvents).with(ISSUE_ROUTING_KEY);
	}

	@Bean
	Binding documentIssueDeadLetterBinding(Queue documentIssueDeadLetterQueue, DirectExchange fiscalDeadLetters) {
		return BindingBuilder.bind(documentIssueDeadLetterQueue).to(fiscalDeadLetters).with(DEAD_LETTER_QUEUE);
	}

}
