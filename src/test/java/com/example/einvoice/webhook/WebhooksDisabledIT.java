package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.example.einvoice.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestCertificates;

/** Como viene de fábrica: la función apagada. */
@TestPropertySource(properties = "einvoice.webhooks.enabled=false")
class WebhooksDisabledIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

	@Autowired
	DueWebhookScheduler scheduler;

	@Autowired
	WebhookDeliveryService webhooks;

	static final String BODY = "{\"url\":\"https://93.184.216.34/x\",\"eventTypes\":[\"document.authorized\"],\"enabled\":true}";

	@Test
	void nothingIsRegisteredOrEnabledWhileTheFeatureIsOff() throws Exception {
		String owner = newUserToken();
		UUID tenant = createTenant(owner);
		// Una suscripción que quedó de cuando estuvo encendida.
		UUID left = jdbc.queryForObject("""
				INSERT INTO webhook_subscriptions (tenant_id, url, event_types, status, disabled_reason, secret, created_by)
				SELECT ?, 'https://93.184.216.34/x', ARRAY['document.authorized'], 'DISABLED', 'MANUAL', '\\x00'::bytea, user_id
				  FROM memberships WHERE tenant_id = ? RETURNING id
				""", UUID.class, tenant, tenant);

		mvc.perform(json(post("/api/v1/webhooks/subscriptions"), owner, BODY).header(TENANT_HEADER, tenant))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("WEBHOOKS_DISABLED"));
		mvc.perform(json(put("/api/v1/webhooks/subscriptions/" + left), owner, BODY).header(TENANT_HEADER, tenant))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("WEBHOOKS_DISABLED"));

		// Lo que hay se sigue viendo, y se puede apagar.
		mvc.perform(as(get("/api/v1/webhooks/subscriptions"), owner).header(TENANT_HEADER, tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)));
		jdbc.update("UPDATE webhook_subscriptions SET status = 'ACTIVE', disabled_reason = NULL WHERE id = ?", left);
		mvc.perform(json(put("/api/v1/webhooks/subscriptions/" + left), owner, BODY.replace("true", "false"))
			.header(TENANT_HEADER, tenant)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
	}

	@Test
	void nothingIsRecordedOrSentWhileTheFeatureIsOff() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		// Una suscripción activa que quedó de cuando estuvo encendida.
		jdbc.update("""
				INSERT INTO webhook_subscriptions (tenant_id, url, event_types, secret, created_by)
				SELECT ?, 'https://93.184.216.34/x', ARRAY['document.received','document.authorized'], '\\x00'::bytea, user_id
				  FROM memberships WHERE tenant_id = ?
				""", s.tenant(), s.tenant());
		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));

		for (int step = 0; step < 10; step++) {
			pipeline.advance(s.tenant(), document);
		}

		assertThat(jdbc.queryForObject("SELECT state FROM fiscal_documents WHERE id = ?", String.class, document))
			.isEqualTo("AUTHORIZED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE tenant_id = ?", Integer.class, s.tenant()))
			.isZero();
		assertThat(scheduler.enqueueDue()).isZero();
		assertThat(webhooks.process(s.tenant(), document)).isZero();
	}

}
