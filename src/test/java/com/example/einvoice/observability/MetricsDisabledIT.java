package com.example.einvoice.observability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.example.einvoice.support.AbstractIntegrationTest;

/** Sin ficha configurada, las métricas no existen para nadie: es el estado por defecto. */
@TestPropertySource(properties = "management.prometheus.metrics.export.enabled=true")
class MetricsDisabledIT extends AbstractIntegrationTest {

	@Test
	void withoutAConfiguredTokenTheEndpointDoesNotExist() throws Exception {
		mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
		mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer ")).andExpect(status().isNotFound());
		mvc.perform(as(get("/actuator/prometheus"), newUserToken())).andExpect(status().isNotFound());
	}

	@Test
	void aTokenTooShortToBeASecretIsRefusedAtStartup() {
		assertThatThrownBy(() -> new MetricsTokenFilter("corta")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("EINVOICE_METRICS_TOKEN");
	}

}
