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
 * Las métricas sobre HTTP de verdad, con los puntos de operación movidos a otra ruta: su protección
 * no debe depender de que la ruta sea la de siempre. Aquí hay ficha configurada.
 */
@RealHttpTest
@TestPropertySource(properties = { "einvoice.metrics.token=" + MetricsOverHttpIT.TOKEN,
		"management.prometheus.metrics.export.enabled=true", "management.endpoints.web.base-path=/interno" })
class MetricsOverHttpIT extends AbstractIntegrationTest {

	static final String TOKEN = "ficha-de-metricas-solo-para-pruebas";

	@LocalServerPort
	int port;

	final HttpClient http = HttpClient.newHttpClient();

	HttpResponse<String> get(String path, String authorization) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void onlyTheMetricsTokenReadsThemWhereverTheyLive() throws Exception {
		assertThat(get("/interno/prometheus", null).statusCode()).isEqualTo(401);
		// Un usuario de la aplicación, con su token válido, no lee métricas tampoco en la ruta nueva.
		HttpResponse<String> asUser = get("/interno/prometheus", newUserToken());
		assertThat(asUser.statusCode()).isEqualTo(401);
		assertThat(asUser.body()).doesNotContain("einvoice_documents");

		HttpResponse<String> scraped = get("/interno/prometheus", "Bearer " + TOKEN);
		assertThat(scraped.statusCode()).isEqualTo(200);
		assertThat(scraped.body()).contains("einvoice_documents");
	}

	@Test
	void theProbesStayOpenWhereverTheyLive() throws Exception {
		assertThat(get("/interno/health/liveness", null).statusCode()).isEqualTo(200);
		assertThat(get("/interno/health/readiness", null).statusCode()).isEqualTo(200);
	}

	@Test
	void theOldAddressNoLongerExists() throws Exception {
		assertThat(get("/actuator/prometheus", "Bearer " + TOKEN).statusCode()).isIn(401, 404);
		assertThat(get("/actuator/prometheus", "Bearer " + TOKEN).body()).doesNotContain("einvoice_documents");
	}

}
