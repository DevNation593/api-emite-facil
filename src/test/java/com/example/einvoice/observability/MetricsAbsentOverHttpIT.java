package com.example.einvoice.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.RealHttpTest;

/**
 * Sin ficha configurada las métricas no existen: 404 para cualquiera, también sobre HTTP de verdad,
 * donde un error pasa por el despacho de errores del contenedor (MockMvc no lo reproduce).
 */
@RealHttpTest
@TestPropertySource(properties = "management.prometheus.metrics.export.enabled=true")
class MetricsAbsentOverHttpIT extends AbstractIntegrationTest {

	@LocalServerPort
	int port;

	final HttpClient http = HttpClient.newHttpClient();

	int status(String authorization) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
	}

	@Test
	void withoutAConfiguredTokenTheEndpointAnswers404ToEveryone() throws Exception {
		assertThat(status(null)).isEqualTo(404);
		assertThat(status("Bearer cualquier-cosa-que-alguien-pruebe-0000")).isEqualTo(404);
		assertThat(status(newUserToken())).isEqualTo(404);
	}

}
