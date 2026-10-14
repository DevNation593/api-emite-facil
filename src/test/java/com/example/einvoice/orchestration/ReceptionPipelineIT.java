package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
import com.example.einvoice.web.ApiException;

/** Envío a la recepción del SRI (simulado) y reconciliación de los envíos de resultado incierto. */
class ReceptionPipelineIT extends AbstractIntegrationTest {

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
		// Por defecto la autorización queda pendiente: estas pruebas tratan de la recepción.
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.processing(key)));
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

	@Test
	void signedInvoiceIsSentAndReceived() {
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");

		assertThat(state()).isEqualTo("RECEIVED");
		assertThat(sri.receptionCalls(key)).isEqualTo(1);
		assertThat(sri.lastReceptionXml(key))
			.isEqualTo(artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED).orElseThrow().content());
		assertThat(transitions()).containsExactly("->RESERVED", "RESERVED>GENERATED", "GENERATED>SIGNED",
				"SIGNED>SENDING", "SENDING>RECEIVED");
		assertThat(attempts("RECEPTION")).containsExactly("RECEIVED");
		assertThat(jdbc.queryForObject(
				"SELECT sri_status || '/' || http_status || '/' || environment FROM sri_attempts"
						+ " WHERE document_id = ? AND operation = 'RECEPTION'",
				String.class, document))
			.isEqualTo("RECIBIDA/200/1");
		assertThat(nextAttemptAt()).isNotNull();
	}

	@Test
	void returnedInvoiceKeepsEveryMessage() {
		sri.onReception(key, Reply.xml(SriSoapSamples.returned(key,
				new Msg("35", "DOCUMENTO INVÁLIDO", "Se encontró un error en la estructura", "ERROR"),
				Msg.error("45", "SECUENCIAL REGISTRADO"))));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RETURNED");

		assertThat(state()).isEqualTo("RETURNED");
		assertThat(lastReason()).isEqualTo("35, 45");
		assertThat(attempts("RECEPTION")).containsExactly("RETURNED");
		assertThat(jdbc.queryForList("""
				SELECT m.identifier || '|' || m.message || '|' || coalesce(m.additional_info, '') || '|' || m.type
				FROM sri_messages m JOIN sri_attempts a ON a.id = m.attempt_id
				WHERE a.document_id = ? ORDER BY m.position
				""", String.class, document)).containsExactly(
				"35|DOCUMENTO INVÁLIDO|Se encontró un error en la estructura|ERROR", "45|SECUENCIAL REGISTRADO||ERROR");
		assertThat(jdbc.queryForObject(
				"SELECT convert_from(response, 'UTF8') FROM sri_attempts WHERE document_id = ? AND operation = 'RECEPTION'",
				String.class, document))
			.contains("<estado>DEVUELTA</estado>");
		assertThat(nextAttemptAt()).isNull();
		assertThat(sri.authorizationCalls(key)).isZero();

		// Un comprobante devuelto no se vuelve a tocar por su cuenta.
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RETURNED");
		assertThat(sri.receptionCalls(key)).isEqualTo(1);
	}

	@Test
	void alreadyRegisteredCountsAsReceived() {
		sri.onReception(key,
				Reply.xml(SriSoapSamples.returned(key, Msg.error("70", "CLAVE DE ACCESO EN PROCESAMIENTO"))));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");

		assertThat(attempts("RECEPTION")).containsExactly("ALREADY_REGISTERED");
		assertThat(jdbc.queryForObject(
				"SELECT sri_status FROM sri_attempts WHERE document_id = ? AND operation = 'RECEPTION'", String.class,
				document))
			.isEqualTo("DEVUELTA");
		assertThat(nextAttemptAt()).isNotNull();
	}

	@Test
	void registeredAccessKeyCountsAsReceived() {
		sri.onReception(key, Reply.xml(SriSoapSamples.returned(key, Msg.error("43", "CLAVE ACCESO REGISTRADA"))));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");
		assertThat(attempts("RECEPTION")).containsExactly("ALREADY_REGISTERED");
	}

	@Test
	void uncertainSendIsNeverResentBlindly() {
		sri.onReception(key, Reply.status(500, "Internal Server Error"));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SENDING");

		assertThat(attempts("RECEPTION")).containsExactly("UNCERTAIN");
		assertThat(attemptCount()).isEqualTo(1);
		assertThat(nextAttemptAt()).isAfter(OffsetDateTime.now());
		// No se consultó nada todavía: la reconciliación llega con el siguiente avance.
		assertThat(sri.authorizationCalls(key)).isZero();

		// El SRI sí lo había recibido: al reconciliar se descubre y no se envía otra vez.
		assertThat(pipeline.advance(s.tenant(), document)).isNotEqualTo("SENDING");

		assertThat(sri.receptionCalls(key)).isEqualTo(1);
		assertThat(sri.authorizationCalls(key)).isGreaterThanOrEqualTo(1);
		assertThat(transitions()).contains("SENDING>RECEIVED").doesNotContain("SENDING>SIGNED");
	}

	@Test
	void uncertainSendIsResentOnlyWhenTheSriDoesNotKnowTheKey() {
		sri.onReception(key, Reply.dropConnection(), Reply.xml(SriSoapSamples.received()));
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.notFound(key)));
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SENDING");

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");

		assertThat(sri.receptionCalls(key)).isEqualTo(2);
		assertThat(transitions()).containsSubsequence("SIGNED>SENDING", "SENDING>SIGNED", "SIGNED>SENDING",
				"SENDING>RECEIVED");
		assertThat(jdbc.queryForObject(
				"SELECT reason FROM state_events WHERE document_id = ? AND previous_state = 'SENDING'"
						+ " AND new_state = 'SIGNED'",
				String.class, document))
			.isEqualTo("RESEND_AFTER_NOT_FOUND");
		assertThat(attempts("RECEPTION")).containsExactly("UNCERTAIN", "RECEIVED");
		assertThat(sri.lastReceptionXml(key))
			.isEqualTo(artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED).orElseThrow().content());
	}

	@Test
	void notFoundThatIsNotAboutThisKeyDoesNotTriggerAResend() {
		sri.onReception(key, Reply.status(500, "Internal Server Error"));
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.notFound("9".repeat(49))),
				Reply.xml(SriSoapSamples.emptyAuthorizationResponse()));
		pipeline.advance(s.tenant(), document);

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SENDING");
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SENDING");

		assertThat(sri.receptionCalls(key)).isEqualTo(1);
		assertThat(attempts("AUTHORIZATION")).containsExactly("MISMATCH", "MISMATCH");
		assertThat(transitions()).doesNotContain("SENDING>SIGNED");
		assertThat(attemptCount()).isEqualTo(3);
	}

	@Test
	void concurrentReconcilersResendOnce() throws Exception {
		sri.onReception(key, Reply.status(500, "Internal Server Error"),
				Reply.delayed(Duration.ofMillis(600), Reply.xml(SriSoapSamples.received())));
		sri.onAuthorization(key, Reply.delayed(Duration.ofMillis(300), Reply.xml(SriSoapSamples.notFound(key))));
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SENDING");
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
				future.get();
			}
		}
		finally {
			pool.shutdownNow();
		}
		// El envío incierto y un único reenvío, aunque los cuatro vieron que el SRI no conocía la clave.
		assertThat(sri.receptionCalls(key)).isEqualTo(2);
		assertThat(transitions()).containsOnlyOnce("SENDING>SIGNED");
		assertThat(state()).isEqualTo("RECEIVED");
	}

	@Test
	void sriDownWhileReconcilingKeepsTheDocumentSending() {
		sri.onReception(key, Reply.status(500, "Internal Server Error"));
		sri.onAuthorization(key, Reply.status(503, "Service Unavailable"));
		pipeline.advance(s.tenant(), document);

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SENDING");

		assertThat(sri.receptionCalls(key)).isEqualTo(1);
		assertThat(attempts("AUTHORIZATION")).containsExactly("UNCERTAIN");
		assertThat(attemptCount()).isEqualTo(2);
		assertThat(nextAttemptAt()).isAfter(OffsetDateTime.now());
	}

	/** Deja el documento como si el proceso hubiera muerto en mitad del envío. */
	void leaveSendInFlightSince(Duration age) {
		jdbc.update("UPDATE fiscal_documents SET state = 'SENDING' WHERE id = ?", document);
		jdbc.update(
				"INSERT INTO sri_attempts (tenant_id, document_id, operation, environment, started_at)"
						+ " VALUES (?, ?, 'RECEPTION', '1', now() - make_interval(secs => ?))",
				s.tenant(), document, (double) age.toSeconds());
	}

	@Test
	void abandonedSendIsResolvedByQueryingFirst() {
		pipeline.advance(s.tenant(), document);
		int receptionsBefore = sri.receptionCalls(key);
		jdbc.update("DELETE FROM sri_messages WHERE attempt_id IN (SELECT id FROM sri_attempts WHERE document_id = ?)",
				document);
		jdbc.update("DELETE FROM sri_attempts WHERE document_id = ?", document);
		leaveSendInFlightSince(Duration.ofMinutes(10));

		assertThat(pipeline.advance(s.tenant(), document)).isNotEqualTo("SENDING");

		assertThat(attempts("RECEPTION")).containsExactly("ABANDONED");
		assertThat(sri.receptionCalls(key)).isEqualTo(receptionsBefore);
		assertThat(sri.authorizationCalls(key)).isGreaterThanOrEqualTo(1);
	}

	@Test
	void sendInFlightIsLeftAlone() {
		pipeline.advance(s.tenant(), document);
		int receptions = sri.receptionCalls(key);
		int authorizations = sri.authorizationCalls(key);
		leaveSendInFlightSince(Duration.ofSeconds(1));

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SENDING");

		assertThat(sri.receptionCalls(key)).isEqualTo(receptions);
		assertThat(sri.authorizationCalls(key)).isEqualTo(authorizations);
		// El intento abierto sigue abierto: nadie lo dio por abandonado.
		assertThat(attempts("RECEPTION")).containsExactlyInAnyOrder("RECEIVED", "IN_FLIGHT");
	}

	@Test
	void concurrentAdvanceSendsOnce() throws Exception {
		sri.onReception(key, Reply.delayed(Duration.ofMillis(500), Reply.xml(SriSoapSamples.received())));
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
				assertThat(future.get()).isIn("SENDING", "RECEIVED");
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(sri.receptionCalls(key)).isEqualTo(1);
		assertThat(state()).isEqualTo("RECEIVED");
		assertThat(transitions()).containsOnlyOnce("SIGNED>SENDING");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM document_artifacts WHERE document_id = ?", Long.class,
				document))
			.isEqualTo(2);
	}

	@Test
	void disabledEnvironmentStaysSignedWithoutCallingTheSri() throws Exception {
		BillingScenario production = newBillingScenario();
		uploadCertificate(production.owner(), production.taxpayer(), TestCertificates.valid());
		// Emisor ya activado en producción; lo que está cerrado es la transmisión de la plataforma.
		jdbc.update("UPDATE taxpayers SET default_environment = '2', production_enabled_at = now() WHERE id = ?",
				production.taxpayer());
		UUID invoice = issueInvoice(production.owner(), production.invoiceJson("1", "11.50"));
		String productionKey = jdbc.queryForObject(
				"SELECT access_key FROM fiscal_documents WHERE id = ? AND environment = '2'", String.class, invoice);

		assertThat(pipeline.advance(production.tenant(), invoice)).isEqualTo("SIGNED");

		assertThat(sri.receptionCalls(productionKey)).isZero();
		assertThat(sri.authorizationCalls(productionKey)).isZero();
		assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM fiscal_documents WHERE id = ?",
				OffsetDateTime.class, invoice))
			.isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM sri_attempts WHERE document_id = ?", Long.class, invoice))
			.isZero();
	}

	@Test
	void retriesAreExhausted() {
		sri.onReception(key, Reply.status(500, "Internal Server Error"));
		sri.onAuthorization(key, Reply.status(503, "Service Unavailable"));
		pipeline.advance(s.tenant(), document);
		// El máximo de intentos de las pruebas es 5.
		jdbc.update("UPDATE fiscal_documents SET attempt_count = 4 WHERE id = ?", document);

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("SENDING");

		assertThat(nextAttemptAt()).isNull();
		assertThat(attemptCount()).isEqualTo(5);
		assertThat(lastReason()).isEqualTo("SRI_RETRIES_EXHAUSTED");
		assertThat(transitions()).last().isEqualTo("SENDING>SENDING");
	}

	@Test
	void signatureFailureDoesNotReachTheSri() throws Exception {
		BillingScenario noCertificate = newBillingScenario();
		UUID invoice = issueInvoice(noCertificate.owner(), noCertificate.invoiceJson("1", "11.50"));
		String otherKey = jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class,
				invoice);

		assertThat(pipeline.advance(noCertificate.tenant(), invoice)).isEqualTo("SIGNATURE_FAILED");

		assertThat(sri.receptionCalls(otherKey)).isZero();
		assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM fiscal_documents WHERE id = ?",
				OffsetDateTime.class, invoice))
			.isNull();
	}

	@Test
	void foreignDocumentIsNotFound() throws Exception {
		UUID otherTenant = createTenant(newUserToken());

		assertThatThrownBy(() -> pipeline.advance(otherTenant, document)).isInstanceOf(ApiException.class);
		assertThat(state()).isEqualTo("RESERVED");
		assertThat(sri.receptionCalls(key)).isZero();
	}

}
