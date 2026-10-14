package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.certificate.CertificateService;
import com.example.einvoice.certificate.SecretCipher;
import com.example.einvoice.signature.XadesVerifier;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.xml.InvoiceEnvelopeRules;
import com.example.einvoice.xml.InvoiceSchemas;
import com.example.einvoice.xml.SchemaVersion;
import com.example.einvoice.xml.XsdValidator;

class DocumentProcessingIT extends AbstractIntegrationTest {

	@Autowired
	DocumentProcessor processor;

	@Autowired
	ArtifactStore artifacts;

	@Autowired
	XadesVerifier verifier;

	@Autowired
	SecretCipher cipher;

	BillingScenario s;

	UUID document;

	@BeforeEach
	void reservedInvoice() throws Exception {
		s = newBillingScenario();
		document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
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

	long artifactCount() {
		return jdbc.queryForObject("SELECT count(*) FROM document_artifacts WHERE document_id = ?", Long.class,
				document);
	}

	@Test
	void reservedInvoiceBecomesSigned() throws Exception {
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());

		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNED");

		assertThat(state()).isEqualTo("SIGNED");
		assertThat(jdbc.queryForObject("SELECT schema_version FROM fiscal_documents WHERE id = ?", String.class,
				document))
			.isEqualTo("1.1.0");
		assertThat(transitions()).containsExactly("->RESERVED", "RESERVED>GENERATED", "GENERATED>SIGNED");
		assertThat(artifactCount()).isEqualTo(2);

		byte[] signed = artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED).orElseThrow().content();
		assertThat(verifier.verify(signed).errors()).isEmpty();
		assertThat(new XsdValidator(new InvoiceSchemas()).validate(signed, SchemaVersion.V1_1_0).errors()).isEmpty();
		assertThat(InvoiceEnvelopeRules.check(signed, SchemaVersion.V1_1_0)).isEmpty();
		String accessKey = jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class,
				document);
		String xml = new String(signed, StandardCharsets.UTF_8);
		assertThat(xml).contains("<claveAcceso>" + accessKey + "</claveAcceso>")
			.contains("<ruc>" + s.ruc() + "</ruc>")
			.contains("<importeTotal>23.00</importeTotal>")
			.contains("<campoAdicional nombre=\"RUC Proveedor\">1790099999001</campoAdicional>");
		byte[] unsigned = artifacts.find(s.tenant(), document, ArtifactKind.XML_UNSIGNED).orElseThrow().content();
		assertThat(new String(unsigned, StandardCharsets.UTF_8)).doesNotContain("Signature");
	}

	@Test
	void signedXmlIsDownloadableAndStateIsVisible() throws Exception {
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		processor.generateAndSign(s.tenant(), document);

		byte[] downloaded = mvc.perform(as(get("/api/v1/documents/" + document + "/artifacts/xml-signed"), s.owner()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(verifier.verify(downloaded).valid()).isTrue();
		mvc.perform(as(get("/api/v1/documents/" + document), s.owner()))
			.andExpect(jsonPath("$.state").value("SIGNED"));
	}

	@Test
	void processingTwiceChangesNothing() throws Exception {
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		processor.generateAndSign(s.tenant(), document);
		String sha = artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED).orElseThrow().sha256();

		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNED");

		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED).orElseThrow().sha256()).isEqualTo(sha);
		assertThat(transitions()).hasSize(3);
		assertThat(artifactCount()).isEqualTo(2);
	}

	@Test
	void concurrentProcessingProducesOneSignedArtifact() throws Exception {
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		int threads = 4;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				Callable<String> worker = () -> {
					start.await();
					return processor.generateAndSign(s.tenant(), document);
				};
				futures.add(pool.submit(worker));
			}
			start.countDown();
			for (Future<String> future : futures) {
				assertThat(future.get()).isEqualTo("SIGNED");
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(artifactCount()).isEqualTo(2);
		assertThat(transitions()).hasSize(3);
	}

	@Test
	void withoutCertificateEndsInSignatureFailedAndCanBeRetried() throws Exception {
		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNATURE_FAILED");
		assertThat(state()).isEqualTo("SIGNATURE_FAILED");
		assertThat(jdbc.queryForObject(
				"SELECT reason FROM state_events WHERE document_id = ? AND new_state = 'SIGNATURE_FAILED'", String.class,
				document))
			.isEqualTo("CERTIFICATE_MISSING");
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_UNSIGNED)).isPresent();
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED)).isEmpty();

		// Reintentar sin haber arreglado nada no añade eventos.
		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNATURE_FAILED");
		assertThat(transitions()).hasSize(3);

		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNED");
		assertThat(transitions()).containsExactly("->RESERVED", "RESERVED>GENERATED", "GENERATED>SIGNATURE_FAILED",
				"SIGNATURE_FAILED>SIGNED");
		assertThat(artifactCount()).isEqualTo(2);
	}

	@Test
	void expiredCertificateEndsInSignatureFailed() {
		// Un certificado que caducó después de cargarse: se inserta ya cifrado, saltando la validación de la carga.
		UUID certificateId = UUID.randomUUID();
		UUID user = jdbc.queryForObject("SELECT created_by FROM fiscal_documents WHERE id = ?", UUID.class, document);
		jdbc.update("""
				INSERT INTO certificates (id, tenant_id, taxpayer_id, subject, issuer, serial_number,
				 fingerprint_sha256, valid_from, valid_to, status, encrypted_p12, encrypted_password, created_by)
				VALUES (?, ?, ?, 'CN=Caducado', 'CN=Caducado', '1', ?, ?, ?, 'ACTIVE', ?, ?, ?)
				""", certificateId, s.tenant(), s.taxpayer(), "a".repeat(64), OffsetDateTime.now().minusDays(30),
				OffsetDateTime.now().minusDays(25),
				cipher.encrypt(TestCertificates.expired(),
						CertificateService.secretContext(s.tenant(), s.taxpayer(), certificateId, "p12")),
				cipher.encrypt(TestCertificates.PASSWORD.getBytes(StandardCharsets.UTF_8),
						CertificateService.secretContext(s.tenant(), s.taxpayer(), certificateId, "password")),
				user);

		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNATURE_FAILED");
		assertThat(jdbc.queryForList(
				"SELECT reason FROM state_events WHERE document_id = ? AND new_state = 'SIGNATURE_FAILED'"
						+ " ORDER BY event_time",
				String.class, document))
			.last()
			.isEqualTo("CERTIFICATE_EXPIRED");
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED)).isEmpty();
	}

	@Test
	void undecryptableCertificateEndsInSignatureFailedKeepingTheGeneratedXml() throws Exception {
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		// Certificado guardado que ya no se puede descifrar (blob dañado o clave maestra cambiada).
		jdbc.update("UPDATE certificates SET encrypted_p12 = set_byte(encrypted_p12, 40,"
				+ " get_byte(encrypted_p12, 40) # 1) WHERE taxpayer_id = ?", s.taxpayer());

		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNATURE_FAILED");

		assertThat(state()).isEqualTo("SIGNATURE_FAILED");
		assertThat(transitions()).containsExactly("->RESERVED", "RESERVED>GENERATED", "GENERATED>SIGNATURE_FAILED");
		assertThat(jdbc.queryForObject(
				"SELECT reason FROM state_events WHERE document_id = ? AND new_state = 'SIGNATURE_FAILED'", String.class,
				document))
			.isEqualTo("CERTIFICATE_UNDECRYPTABLE");
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_UNSIGNED)).isPresent();

		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.anotherValid());
		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNED");
	}

	@Test
	void retryThatFailsForAnotherReasonRecordsIt() {
		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNATURE_FAILED");
		expiredCertificateEndsInSignatureFailed();

		assertThat(jdbc.queryForList(
				"SELECT reason FROM state_events WHERE document_id = ? AND new_state = 'SIGNATURE_FAILED'"
						+ " ORDER BY event_time",
				String.class, document))
			.containsExactly("CERTIFICATE_MISSING", "CERTIFICATE_EXPIRED");
		// El mismo fallo repetido no añade más eventos.
		processor.generateAndSign(s.tenant(), document);
		assertThat(transitions()).hasSize(4);
	}

	@Test
	void signerFailureEndsInSignatureFailedInsteadOfLosingTheAttempt() throws Exception {
		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNATURE_FAILED");
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		// El XML generado se dañó en el almacenamiento: el firmador no puede leerlo.
		// La base no deja modificar un artefacto: para simular el daño hay que retirar antes su protección.
		jdbc.execute("ALTER TABLE document_artifacts DISABLE TRIGGER document_artifacts_immutable");
		try {
			jdbc.update("UPDATE document_artifacts SET content = ? WHERE document_id = ?",
					"esto no es un XML".getBytes(StandardCharsets.UTF_8), document);
		}
		finally {
			jdbc.execute("ALTER TABLE document_artifacts ENABLE TRIGGER document_artifacts_immutable");
		}

		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("SIGNATURE_FAILED");

		assertThat(jdbc.queryForList(
				"SELECT reason FROM state_events WHERE document_id = ? AND new_state = 'SIGNATURE_FAILED'"
						+ " ORDER BY event_time",
				String.class, document))
			.containsExactly("CERTIFICATE_MISSING", "SIGNING_ERROR");
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED)).isEmpty();
	}

	@Test
	void documentOfAnotherTenantIsNotFound() throws Exception {
		BillingScenario other = newBillingScenario();
		assertThatThrownBy(() -> processor.generateAndSign(other.tenant(), document)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> processor.generateAndSign(s.tenant(), UUID.randomUUID()))
			.isInstanceOf(ApiException.class);
		assertThat(state()).isEqualTo("RESERVED");
		assertThat(artifactCount()).isZero();
	}

	@Test
	void invalidXmlEndsInXsdInvalid() throws Exception {
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		jdbc.update("UPDATE fiscal_documents SET issuer_legal_name = repeat('x', 301) WHERE id = ?", document);

		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("XSD_INVALID");

		assertThat(state()).isEqualTo("XSD_INVALID");
		assertThat(jdbc.queryForObject(
				"SELECT reason FROM state_events WHERE document_id = ? AND new_state = 'XSD_INVALID'", String.class,
				document))
			.contains("razonSocial");
		assertThat(artifactCount()).isZero();
		// No se reintenta solo: el documento necesita corrección.
		assertThat(processor.generateAndSign(s.tenant(), document)).isEqualTo("XSD_INVALID");
		assertThat(transitions()).hasSize(2);
	}

}
