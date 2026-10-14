package com.example.einvoice.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.TestPropertySource;

import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestCertificates;
import com.jayway.jsonpath.JsonPath;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Lo que la operación necesita ver, y solo para quien tiene la ficha de métricas. */
@TestPropertySource(properties = { "einvoice.metrics.token=" + OperationalMetricsIT.TOKEN,
		// Las pruebas de Spring apagan los exportadores de métricas salvo que se pida.
		"management.prometheus.metrics.export.enabled=true" })
class OperationalMetricsIT extends AbstractIntegrationTest {

	static final String TOKEN = "ficha-de-metricas-solo-para-pruebas";

	@Autowired
	DocumentPipeline pipeline;

	@Autowired
	OperationalMetrics metrics;

	@Autowired
	DataSource dataSource;

	@Autowired
	AmqpAdmin amqp;

	String scrape() throws Exception {
		metrics.refresh();
		return mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	/** Valor de la primera muestra cuya línea empieza por el nombre y contiene todas las etiquetas dadas. */
	static double sample(String text, String name, String... labels) {
		for (String line : text.split("\n")) {
			if (!line.startsWith(name + "{") && !line.startsWith(name + " ")) {
				continue;
			}
			boolean all = true;
			for (String label : labels) {
				all &= line.contains(label);
			}
			if (all) {
				return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
			}
		}
		throw new AssertionError("No hay muestra de " + name + " con " + String.join(",", labels));
	}

	String advanceUntil(BillingScenario s, UUID document, String wanted) {
		String state = "";
		for (int step = 0; step < 10 && !wanted.equals(state); step++) {
			state = pipeline.advance(s.tenant(), document);
		}
		return state;
	}

	@Test
	void onlyTheMetricsTokenOpensTheEndpoint() throws Exception {
		mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer otra-ficha-que-no-es-la-buena"))
			.andExpect(status().isUnauthorized());
		// Un usuario de la aplicación, con su token válido, no es quien recoge métricas.
		mvc.perform(as(get("/actuator/prometheus"), newUserToken())).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
	}

	/** Una base que se puede "caer" a voluntad, para ver qué publican las métricas mientras tanto. */
	static class SwitchableDataSource extends DelegatingDataSource {

		volatile boolean down;

		SwitchableDataSource(DataSource target) {
			super(target);
		}

		@Override
		public Connection getConnection() throws SQLException {
			if (down) {
				throw new SQLException("base no disponible");
			}
			return super.getConnection();
		}

	}

	@Test
	void aDatabaseOutageKeepsTheLastReadingInsteadOfPublishingZeros() throws Exception {
		BillingScenario s = newBillingScenario();
		issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		SwitchableDataSource database = new SwitchableDataSource(dataSource);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		OperationalMetrics own = new OperationalMetrics(JdbcClient.create(database), amqp, registry);
		own.refresh();
		double reserved = registry.get("einvoice.documents").tag("state", "RESERVED").gauge().value();
		double pending = registry.get("einvoice.outbox.pending").gauge().value();
		assertThat(reserved).isPositive();

		database.down = true;
		own.refresh();

		// Con ceros, las alertas que estaban sonando se darían por resueltas justo durante la caída.
		assertThat(registry.get("einvoice.documents").tag("state", "RESERVED").gauge().value()).isEqualTo(reserved);
		assertThat(registry.get("einvoice.outbox.pending").gauge().value()).isEqualTo(pending);
	}

	@Test
	void theAgeOfAWaitingDocumentCountsFromWhenItEnteredItsState() throws Exception {
		BillingScenario s = newBillingScenario();
		UUID document = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		// Un comprobante antiguo que acaba de cambiar de estado no lleva "un mes atascado".
		jdbc.update("UPDATE fiscal_documents SET created_at = now() - interval '30 days' WHERE id = ?", document);

		double oldest = sample(scrape(), "einvoice_documents_oldest_seconds", "state=\"RESERVED\"");

		assertThat(oldest).isLessThan(Duration.ofDays(29).toSeconds());
		// Y el reloj se pone a cero en cada cambio de estado.
		advanceUntil(s, document, "SIGNATURE_FAILED");
		assertThat(jdbc.queryForObject("""
				SELECT d.state_changed_at > now() - interval '1 minute'
				   AND abs(extract(epoch FROM d.state_changed_at
				       - (SELECT max(event_time) FROM state_events e WHERE e.document_id = d.id))) < 1
				  FROM fiscal_documents d WHERE d.id = ?
				""", Boolean.class, document)).isTrue();
	}

	@Test
	void reportsWhatIsWaitingAndWhatTheSriAnswered() throws Exception {
		double reservedBefore = sample(scrape(), "einvoice_documents", "state=\"RESERVED\"");
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		UUID authorized = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		assertThat(advanceUntil(s, authorized, "AUTHORIZED")).isEqualTo("AUTHORIZED");
		// Emitida y aún sin procesar: espera en RESERVED.
		issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));

		String text = scrape();

		assertThat(sample(text, "einvoice_documents", "state=\"RESERVED\"")).isEqualTo(reservedBefore + 1);
		assertThat(sample(text, "einvoice_documents_oldest_seconds", "state=\"RESERVED\"")).isPositive();
		// Los autorizados no se cuentan: son todos los demás los que pueden necesitar atención.
		assertThat(text).doesNotContain("einvoice_documents{state=\"AUTHORIZED\"");
		assertThat(sample(text, "einvoice_sri_calls_seconds_count", "operation=\"RECEPTION\"", "environment=\"1\""))
			.isGreaterThanOrEqualTo(1);
		assertThat(sample(text, "einvoice_sri_calls_seconds_count", "operation=\"AUTHORIZATION\"",
				"outcome=\"AUTHORIZED\""))
			.isGreaterThanOrEqualTo(1);
		// El worker está apagado en estas pruebas: los eventos emitidos siguen por publicar.
		assertThat(sample(text, "einvoice_outbox_pending")).isGreaterThanOrEqualTo(1);
		assertThat(sample(text, "einvoice_outbox_oldest_seconds")).isGreaterThanOrEqualTo(0);
		assertThat(sample(text, "einvoice_queue_messages", "queue=\"document.issue.dlq\"")).isGreaterThanOrEqualTo(0);
		assertThat(sample(text, "einvoice_deliveries", "status=\"FAILED\"")).isGreaterThanOrEqualTo(0);
		assertThat(sample(text, "einvoice_certificates_expiring")).isGreaterThanOrEqualTo(0);
	}

	@Test
	void reportsNotificationsWaitingAndFailedAndSubscriptionsThatSwitchedThemselvesOff() throws Exception {
		String before = scrape();
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		// Nadie procesa los avisos en esta prueba: quedan como se anotan.
		String subscription = JsonPath.read(mvc
			.perform(json(post("/api/v1/webhooks/subscriptions"), s.owner(),
					"{\"url\":\"http://127.0.0.1:9/x\",\"eventTypes\":[\"document.authorized\"]}"))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id");
		UUID failed = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		advanceUntil(s, failed, "AUTHORIZED");
		advanceUntil(s, issueInvoice(s.owner(), s.invoiceJson("2", "23.00")), "AUTHORIZED");
		jdbc.update("UPDATE webhook_deliveries SET status = 'FAILED', next_attempt_at = NULL WHERE document_id = ?", failed);
		jdbc.update("UPDATE webhook_subscriptions SET status = 'DISABLED', disabled_reason = 'TOO_MANY_FAILURES' WHERE id = ?",
				UUID.fromString(subscription));

		String text = scrape();

		assertThat(sample(text, "einvoice_webhooks", "status=\"PENDING\""))
			.isEqualTo(sample(before, "einvoice_webhooks", "status=\"PENDING\"") + 1);
		assertThat(sample(text, "einvoice_webhooks", "status=\"FAILED\""))
			.isEqualTo(sample(before, "einvoice_webhooks", "status=\"FAILED\"") + 1);
		assertThat(sample(text, "einvoice_webhooks", "status=\"SENDING\"")).isGreaterThanOrEqualTo(0);
		assertThat(text).doesNotContain("einvoice_webhooks{status=\"SENT\"");
		// Las que apagó la propia empresa no cuentan: no son un problema.
		assertThat(sample(text, "einvoice_webhooks_disabled")).isEqualTo(sample(before, "einvoice_webhooks_disabled") + 1);
		assertThat(sample(text, "einvoice_queue_messages", "queue=\"webhook.deliver\"")).isGreaterThanOrEqualTo(0);
		assertThat(sample(text, "einvoice_queue_messages", "queue=\"webhook.deliver.dlq\"")).isGreaterThanOrEqualTo(0);
	}

	@Test
	void aCertificateAboutToExpireIsCounted() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		double before = sample(scrape(), "einvoice_certificates_expiring");

		jdbc.update("UPDATE certificates SET valid_to = now() + interval '10 days' WHERE taxpayer_id = ?", s.taxpayer());

		assertThat(sample(scrape(), "einvoice_certificates_expiring")).isEqualTo(before + 1);
	}

	@Test
	void metricsIdentifyNoCompanyAndNoPerson() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		advanceUntil(s, issueInvoice(s.owner(), s.invoiceJson("2", "23.00")), "AUTHORIZED");

		String text = scrape();

		assertThat(text).doesNotContain(s.ruc()).doesNotContain("Comercial Andina").doesNotContain("1712345678");
		Pattern uuid = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
		for (String line : text.split("\n")) {
			if (line.startsWith("einvoice_")) {
				assertThat(uuid.matcher(line).find()).as(line).isFalse();
			}
		}
	}

	@Test
	void everyAlertRuleNamesAMetricThatExists() throws Exception {
		String rules = Files.readString(Path.of("..", "infra", "observability", "prometheus-alerts.yml"));
		Set<String> named = new TreeSet<>();
		Matcher matcher = Pattern.compile("einvoice_[a-z_]+").matcher(rules);
		while (matcher.find()) {
			named.add(matcher.group());
		}
		assertThat(named).hasSizeGreaterThanOrEqualTo(6);
		// Deja al menos una llamada al SRI medida: su temporizador nace con la primera.
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		advanceUntil(s, issueInvoice(s.owner(), s.invoiceJson("2", "23.00")), "AUTHORIZED");

		String text = "\n" + scrape();

		for (String metric : named) {
			assertThat(text.contains("\n" + metric + "{") || text.contains("\n" + metric + " ")).as(metric).isTrue();
		}
	}

	@Test
	void theProbesNeedNoCredentials() throws Exception {
		mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
	}

}
