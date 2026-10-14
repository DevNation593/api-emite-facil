package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SriSoapSamples;
import com.example.einvoice.support.SriSoapSamples.Msg;
import com.example.einvoice.support.SriStub;
import com.example.einvoice.support.SriStub.Reply;
import com.example.einvoice.support.TestCertificates;

/** Consulta de autorización al SRI (simulado) y cierre del comprobante. */
class AuthorizationPipelineIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

	@Autowired
	ArtifactStore artifacts;

	final SriStub sri = SriStub.instance();

	BillingScenario s;

	UUID document;

	String key;

	@BeforeEach
	void invoiceOfAnIssuerWithCertificate() throws Exception {
		s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		key = jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class, document);
	}

	String state() {
		return jdbc.queryForObject("SELECT state FROM fiscal_documents WHERE id = ?", String.class, document);
	}

	List<String> transitions() {
		return jdbc.queryForList(
				"SELECT coalesce(previous_state, '-') || '>' || new_state FROM state_events WHERE document_id = ?"
						+ " ORDER BY event_time",
				String.class, document);
	}

	String lastReason() {
		return jdbc.queryForObject(
				"SELECT reason FROM state_events WHERE document_id = ? ORDER BY event_time DESC LIMIT 1", String.class,
				document);
	}

	List<String> attempts(String operation) {
		return jdbc.queryForList(
				"SELECT coalesce(outcome, 'IN_FLIGHT') FROM sri_attempts WHERE document_id = ? AND operation = ?"
						+ " ORDER BY started_at",
				String.class, document, operation);
	}

	OffsetDateTime nextAttemptAt() {
		return jdbc.queryForObject("SELECT next_attempt_at FROM fiscal_documents WHERE id = ?", OffsetDateTime.class,
				document);
	}

	int attemptCount() {
		return jdbc.queryForObject("SELECT attempt_count FROM fiscal_documents WHERE id = ?", Integer.class, document);
	}

	boolean hasAuthorizationArtifact() {
		return artifacts.find(s.tenant(), document, ArtifactKind.SRI_AUTHORIZATION).isPresent();
	}

	@Test
	void receivedInvoiceBecomesAuthorized() {
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");

		assertThat(state()).isEqualTo("AUTHORIZED");
		assertThat(jdbc.queryForObject("SELECT authorization_number FROM fiscal_documents WHERE id = ?", String.class,
				document))
			.isEqualTo(key);
		assertThat(jdbc.queryForObject("SELECT authorized_at FROM fiscal_documents WHERE id = ?", OffsetDateTime.class,
				document))
			.isEqualTo(OffsetDateTime.parse(SriSoapSamples.AUTHORIZATION_DATE));
		assertThat(new String(artifacts.find(s.tenant(), document, ArtifactKind.SRI_AUTHORIZATION).orElseThrow().content(),
				StandardCharsets.UTF_8))
			.isEqualTo(SriSoapSamples.authorized(key));
		// Queda programado hasta que se archive (lo cubre DocumentArchiverIT).
		assertThat(nextAttemptAt()).isAfter(OffsetDateTime.now());
		assertThat(transitions()).containsExactly("->RESERVED", "RESERVED>GENERATED", "GENERATED>SIGNED",
				"SIGNED>SENDING", "SENDING>RECEIVED", "RECEIVED>AUTHORIZED");
		assertThat(attempts("AUTHORIZATION")).containsExactly("AUTHORIZED");
		// La respuesta ya está en el artefacto: el intento no la duplica, pero conserva la advertencia.
		assertThat(jdbc.queryForObject(
				"SELECT response IS NULL FROM sri_attempts WHERE document_id = ? AND operation = 'AUTHORIZATION'",
				Boolean.class, document))
			.isTrue();
		assertThat(jdbc.queryForList("""
				SELECT m.identifier || '|' || m.type FROM sri_messages m JOIN sri_attempts a ON a.id = m.attempt_id
				WHERE a.document_id = ? AND a.operation = 'AUTHORIZATION'
				""", String.class, document)).containsExactly("60|ADVERTENCIA");
	}

	@Test
	void notAuthorizedKeepsTheReasons() {
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.notAuthorized(key, "NO AUTORIZADO",
				Msg.error("39", "FIRMA INVALIDA"), Msg.error("52", "ERROR EN DIFERENCIAS"))));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("NOT_AUTHORIZED");

		assertThat(state()).isEqualTo("NOT_AUTHORIZED");
		assertThat(lastReason()).isEqualTo("39, 52");
		assertThat(hasAuthorizationArtifact()).isFalse();
		assertThat(nextAttemptAt()).isNull();
		assertThat(jdbc.queryForObject(
				"SELECT sri_status || '|' || convert_from(response, 'UTF8') FROM sri_attempts"
						+ " WHERE document_id = ? AND operation = 'AUTHORIZATION'",
				String.class, document))
			.startsWith("NO AUTORIZADO|")
			.contains("FIRMA INVALIDA");

		// Un comprobante no autorizado no se vuelve a consultar por su cuenta.
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("NOT_AUTHORIZED");
		assertThat(sri.authorizationCalls(key)).isEqualTo(1);
	}

	@Test
	void processingIsPolledAgainLater() {
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.processing(key)), Reply.xml(SriSoapSamples.authorized(key)));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");

		assertThat(attempts("AUTHORIZATION")).containsExactly("PROCESSING");
		assertThat(attemptCount()).isEqualTo(1);
		assertThat(nextAttemptAt()).isAfter(OffsetDateTime.now());

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");
		assertThat(attempts("AUTHORIZATION")).containsExactly("PROCESSING", "AUTHORIZED");
		assertThat(attemptCount()).isZero();
		assertThat(sri.receptionCalls(key)).isEqualTo(1);
	}

	@Test
	void notFoundAfterReceptionKeepsWaiting() {
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.notFound(key)));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");

		assertThat(attempts("AUTHORIZATION")).containsExactly("NOT_FOUND", "NOT_FOUND");
		assertThat(sri.receptionCalls(key)).isEqualTo(1);
		assertThat(attemptCount()).isEqualTo(2);
	}

	@Test
	void authorizationForAnotherKeyOrEnvironmentIsNotAccepted() {
		String otherKey = "9".repeat(49);
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.authorized(otherKey)),
				Reply.xml(SriSoapSamples.authorizedIn(key, "PRODUCCIÓN")),
				Reply.xml(SriSoapSamples.notAuthorized(otherKey, "NO AUTORIZADO", Msg.error("39", "FIRMA INVALIDA"))));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");

		assertThat(attempts("AUTHORIZATION")).containsExactly("MISMATCH", "MISMATCH", "MISMATCH");
		assertThat(hasAuthorizationArtifact()).isFalse();
		assertThat(jdbc.queryForObject("SELECT authorization_number FROM fiscal_documents WHERE id = ?", String.class,
				document))
			.isNull();
		// La respuesta que no se aceptó queda guardada como evidencia.
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM sri_attempts WHERE document_id = ? AND outcome = 'MISMATCH' AND response IS NOT NULL",
				Long.class, document))
			.isEqualTo(3);
	}

	@Test
	void sriDownWhileAuthorizingIsRetried() {
		sri.onAuthorization(key, Reply.status(503, "Service Unavailable"));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");

		assertThat(attempts("AUTHORIZATION")).containsExactly("UNCERTAIN");
		assertThat(nextAttemptAt()).isAfter(OffsetDateTime.now());
	}

	@Test
	void authorizationRetriesAreExhausted() {
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.processing(key)));
		pipeline.advance(s.tenant(), document);
		// El máximo de intentos de las pruebas es 5.
		jdbc.update("UPDATE fiscal_documents SET attempt_count = 4 WHERE id = ?", document);

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");

		assertThat(nextAttemptAt()).isNull();
		assertThat(lastReason()).isEqualTo("SRI_RETRIES_EXHAUSTED");
		assertThat(transitions()).last().isEqualTo("RECEIVED>RECEIVED");
	}

	@Test
	void uncertainSendThatWasAuthorizedEndsAuthorized() {
		sri.onReception(key, Reply.status(500, "Internal Server Error"));
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SENDING");

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");

		assertThat(sri.receptionCalls(key)).isEqualTo(1);
		assertThat(hasAuthorizationArtifact()).isTrue();
		assertThat(transitions()).endsWith("SIGNED>SENDING", "SENDING>RECEIVED", "RECEIVED>AUTHORIZED");
	}

	@Test
	void authorizedDocumentIsNeverTouchedAgain() {
		pipeline.advance(s.tenant(), document);
		long version = jdbc.queryForObject("SELECT version FROM fiscal_documents WHERE id = ?", Long.class, document);
		int receptions = sri.receptionCalls(key);
		int authorizations = sri.authorizationCalls(key);

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");

		assertThat(sri.receptionCalls(key)).isEqualTo(receptions);
		assertThat(sri.authorizationCalls(key)).isEqualTo(authorizations);
		assertThat(jdbc.queryForObject("SELECT version FROM fiscal_documents WHERE id = ?", Long.class, document))
			.isEqualTo(version);
	}

	@Test
	void concurrentAuthorizationStoresOneArtifact() throws Exception {
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.processing(key)), Reply.xml(SriSoapSamples.authorized(key)));
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");
		int threads = 4;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				futures.add(pool.submit(() -> {
					start.await();
					return pipeline.advance(s.tenant(), document);
				}));
			}
			start.countDown();
			for (Future<String> future : futures) {
				assertThat(future.get()).isEqualTo("AUTHORIZED");
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM document_artifacts WHERE document_id = ? AND kind = 'SRI_AUTHORIZATION'",
				Long.class, document))
			.isEqualTo(1);
		assertThat(transitions()).containsOnlyOnce("RECEIVED>AUTHORIZED");
	}

	@Test
	void artifactIsDownloadable() throws Exception {
		pipeline.advance(s.tenant(), document);

		byte[] downloaded = mvc
			.perform(as(get("/api/v1/documents/" + document + "/artifacts/sri-authorization"), s.owner()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();

		assertThat(new String(downloaded, StandardCharsets.UTF_8)).isEqualTo(SriSoapSamples.authorized(key));
	}

}
