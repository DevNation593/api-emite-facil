package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.signature.XadesVerifier;
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
import com.example.einvoice.xml.SecureXml;

/** Archivo del comprobante autorizado: XML autorizado y RIDE como artefactos descargables. */
class DocumentArchiverIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

	@Autowired
	DocumentArchiver archiver;

	@Autowired
	ArtifactStore artifacts;

	@Autowired
	XadesVerifier verifier;

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

	byte[] artifact(ArtifactKind kind) {
		return artifacts.find(s.tenant(), document, kind).orElseThrow().content();
	}

	long artifactCount() {
		return jdbc.queryForObject("SELECT count(*) FROM document_artifacts WHERE document_id = ?", Long.class,
				document);
	}

	long authorizedEvents() {
		return jdbc.queryForObject(
				"SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'DOCUMENT_AUTHORIZED'",
				Long.class, document);
	}

	OffsetDateTime nextAttemptAt() {
		return jdbc.queryForObject("SELECT next_attempt_at FROM fiscal_documents WHERE id = ?", OffsetDateTime.class,
				document);
	}

	@Test
	void authorizedInvoiceIsArchivedWithXmlAndRide() throws Exception {
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");

		assertThat(archiver.archive(s.tenant(), document)).isTrue();

		// El comprobante que va dentro del XML autorizado es, byte a byte, el que se firmó y envió.
		byte[] signed = artifact(ArtifactKind.XML_SIGNED);
		String embedded = SecureXml.parse(artifact(ArtifactKind.XML_AUTHORIZED))
			.getDocumentElement()
			.getElementsByTagName("comprobante")
			.item(0)
			.getTextContent();
		assertThat(embedded.getBytes(StandardCharsets.UTF_8)).isEqualTo(signed);
		assertThat(verifier.verify(embedded.getBytes(StandardCharsets.UTF_8)).errors()).isEmpty();
		assertThat(new String(artifact(ArtifactKind.XML_AUTHORIZED), StandardCharsets.UTF_8))
			.contains("<numeroAutorizacion>" + key + "</numeroAutorizacion>", "<ambiente>PRUEBAS</ambiente>",
					"<fechaAutorizacion>2012-03-05T16:57:34.997-05:00</fechaAutorizacion>");

		PdfReader reader = new PdfReader(artifact(ArtifactKind.RIDE_PDF));
		try {
			String text = new PdfTextExtractor(reader).getTextFromPage(1).replaceAll("\\s+", " ");
			assertThat(text).contains(key, s.ruc(), "001-001-", "TOTAL", "23.00", "María Pérez");
		}
		finally {
			reader.close();
		}
		assertThat(artifactCount()).isEqualTo(5);
		assertThat(nextAttemptAt()).isNull();
		assertThat(jdbc.queryForObject("SELECT state FROM fiscal_documents WHERE id = ?", String.class, document))
			.isEqualTo("AUTHORIZED");
	}

	@Test
	void archivingTwiceChangesNothing() {
		pipeline.advance(s.tenant(), document);
		archiver.archive(s.tenant(), document);
		String ride = artifacts.find(s.tenant(), document, ArtifactKind.RIDE_PDF).orElseThrow().sha256();
		String xml = artifacts.find(s.tenant(), document, ArtifactKind.XML_AUTHORIZED).orElseThrow().sha256();

		assertThat(archiver.archive(s.tenant(), document)).isTrue();

		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.RIDE_PDF).orElseThrow().sha256()).isEqualTo(ride);
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_AUTHORIZED).orElseThrow().sha256())
			.isEqualTo(xml);
		assertThat(artifactCount()).isEqualTo(5);
	}

	@Test
	void concurrentArchivingStoresEachArtifactOnce() throws Exception {
		pipeline.advance(s.tenant(), document);
		int threads = 4;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				futures.add(pool.submit(() -> {
					start.await();
					return archiver.archive(s.tenant(), document);
				}));
			}
			start.countDown();
			for (Future<Boolean> future : futures) {
				assertThat(future.get()).isTrue();
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(artifactCount()).isEqualTo(5);
	}

	@Test
	void onlyAuthorizedDocumentsAreArchived() {
		assertThat(archiver.archive(s.tenant(), document)).as("RESERVED").isFalse();

		sri.onAuthorization(key, Reply.xml(SriSoapSamples.processing(key)),
				Reply.xml(SriSoapSamples.notAuthorized(key, "NO AUTORIZADO", Msg.error("39", "FIRMA INVALIDA"))));
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("RECEIVED");
		assertThat(archiver.archive(s.tenant(), document)).as("RECEIVED").isFalse();

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("NOT_AUTHORIZED");
		assertThat(archiver.archive(s.tenant(), document)).as("NOT_AUTHORIZED").isFalse();

		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.RIDE_PDF)).isEmpty();
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_AUTHORIZED)).isEmpty();
	}

	@Test
	void foreignDocumentIsNotFound() throws Exception {
		pipeline.advance(s.tenant(), document);
		UUID otherTenant = createTenant(newUserToken());

		assertThatThrownBy(() -> archiver.archive(otherTenant, document)).isInstanceOf(ApiException.class);
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.RIDE_PDF)).isEmpty();
	}

	@Test
	void authorizationLeavesAnEventAndASafetyNet() {
		pipeline.advance(s.tenant(), document);

		assertThat(authorizedEvents()).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT tenant_id::text FROM outbox_events WHERE aggregate_id = ? AND event_type = 'DOCUMENT_AUTHORIZED'",
				String.class, document))
			.isEqualTo(s.tenant().toString());
		// Si ese evento se pierde, el comprobante se retoma al vencer este plazo y lo vuelve a emitir.
		assertThat(nextAttemptAt()).isBetween(OffsetDateTime.now().plusMinutes(4), OffsetDateTime.now().plusMinutes(6));
		jdbc.update("UPDATE fiscal_documents SET next_attempt_at = now() WHERE id = ?", document);

		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");
		assertThat(authorizedEvents()).isEqualTo(2);
		assertThat(nextAttemptAt()).isAfter(OffsetDateTime.now().plusMinutes(4));

		archiver.archive(s.tenant(), document);
		jdbc.update("UPDATE fiscal_documents SET next_attempt_at = now() WHERE id = ?", document);

		// Ya archivado: nada más que hacer y nada más programado.
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");
		assertThat(authorizedEvents()).isEqualTo(2);
		assertThat(nextAttemptAt()).isNull();
	}

	/** Secuencial del comprobante con sus nueve dígitos. */
	String sequenceNumber() {
		return jdbc.queryForObject("SELECT lpad(sequence::text, 9, '0') FROM fiscal_documents WHERE id = ?",
				String.class, document);
	}

	@Test
	void artifactsAreDownloadableWithTheirContentType() throws Exception {
		pipeline.advance(s.tenant(), document);
		mvc.perform(as(get("/api/v1/documents/" + document + "/artifacts/ride"), s.owner()))
			.andExpect(status().isNotFound());
		mvc.perform(as(get("/api/v1/documents/" + document + "/artifacts/xml-authorized"), s.owner()))
			.andExpect(status().isNotFound());

		archiver.archive(s.tenant(), document);

		MvcResult ride = mvc.perform(as(get("/api/v1/documents/" + document + "/artifacts/ride"), s.owner()))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
			.andExpect(header().exists("ETag"))
			// Se descarga con el número del comprobante, no se abre dentro del sitio.
			.andExpect(header().string("Content-Disposition",
					"attachment; filename=\"factura-001-001-" + sequenceNumber() + "-ride.pdf\""))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andReturn();
		assertThat(ride.getResponse().getContentAsByteArray()).isEqualTo(artifact(ArtifactKind.RIDE_PDF));
		MvcResult xml = mvc
			.perform(as(get("/api/v1/documents/" + document + "/artifacts/xml-authorized"), s.owner()))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", MediaType.APPLICATION_XML_VALUE))
			.andExpect(header().string("Content-Disposition",
					"attachment; filename=\"factura-001-001-" + sequenceNumber() + "-xml-authorized.xml\""))
			.andReturn();
		assertThat(xml.getResponse().getContentAsByteArray()).isEqualTo(artifact(ArtifactKind.XML_AUTHORIZED));

		BillingScenario other = newBillingScenario();
		mvc.perform(as(get("/api/v1/documents/" + document + "/artifacts/ride"), other.owner()))
			.andExpect(status().isNotFound());
	}

}
