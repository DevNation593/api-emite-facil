package com.example.einvoice.webhook;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.example.einvoice.support.AbstractIntegrationTest;

/** Como en producción: sin los interruptores de desarrollo. */
@TestPropertySource(properties = { "einvoice.webhooks.allow-insecure-http=false",
		"einvoice.webhooks.allow-private-addresses=false" })
class WebhookStrictPolicyIT extends AbstractIntegrationTest {

	@Test
	void anAddressThatIsNotPublicHttpsIsNotRegistered() throws Exception {
		String owner = newUserToken();
		UUID tenant = createTenant(owner);

		for (String url : List.of("http://93.184.216.34/x", "https://localhost/x", "https://127.0.0.1/x", "https://10.0.0.1/x",
				"https://169.254.169.254/latest/meta-data", "https://[::1]/x", "https://192.168.1.10:8443/x")) {
			mvc.perform(json(post("/api/v1/webhooks/subscriptions"), owner,
					"{\"url\":\"" + url + "\",\"eventTypes\":[\"document.authorized\"]}")
				.header(TENANT_HEADER, tenant))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errorCode").value("WEBHOOK_URL_NOT_ALLOWED"));
		}
		mvc.perform(json(post("/api/v1/webhooks/subscriptions"), owner,
				"{\"url\":\"https://93.184.216.34/x\",\"eventTypes\":[\"document.authorized\"]}")
			.header(TENANT_HEADER, tenant)).andExpect(status().isCreated());
		mvc.perform(as(get("/api/v1/webhooks/subscriptions"), owner).header(TENANT_HEADER, tenant))
			.andExpect(jsonPath("$", hasSize(1)));
	}

}
