package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessagingConfigTest {

	/** Los INVOICE_RESERVED que queden del despliegue anterior siguen yendo a la cola de emisión. */
	@Test
	void legacyAndNewReservedEventsGoToIssue() {
		assertThat(MessagingConfig.routingKey("DOCUMENT_RESERVED")).isEqualTo(MessagingConfig.ISSUE_ROUTING_KEY);
		assertThat(MessagingConfig.routingKey("INVOICE_RESERVED")).isEqualTo(MessagingConfig.ISSUE_ROUTING_KEY);
	}

}
