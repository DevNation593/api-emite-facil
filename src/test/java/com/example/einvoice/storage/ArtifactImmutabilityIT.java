package com.example.einvoice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.einvoice.orchestration.DocumentProcessor;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;

/** Los artefactos de un comprobante son de solo inserción, y es la base quien lo impone. */
class ArtifactImmutabilityIT extends AbstractIntegrationTest {

	@Autowired
	DocumentProcessor processor;

	@Autowired
	ArtifactStore artifacts;

	BillingScenario s;

	UUID document;

	@BeforeEach
	void generatedInvoice() throws Exception {
		s = newBillingScenario();
		document = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		// Sin certificado: queda generado el XML sin firmar.
		processor.generateAndSign(s.tenant(), document);
	}

	String sha() {
		return artifacts.find(s.tenant(), document, ArtifactKind.XML_UNSIGNED).orElseThrow().sha256();
	}

	@Test
	void artifactsCannotBeUpdatedDeletedOrTruncated() {
		String before = sha();
		byte[] other = "<otra/>".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> jdbc.update("UPDATE document_artifacts SET content = ? WHERE document_id = ?", other,
				document))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE document_artifacts SET sha256 = ? WHERE document_id = ?",
				"0".repeat(64), document))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("DELETE FROM document_artifacts WHERE document_id = ?", document))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE document_artifacts"))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(sha()).isEqualTo(before);
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_UNSIGNED).orElseThrow().content())
			.isNotEqualTo(other);
	}

	@Test
	void newKindsAreAccepted() {
		String insert = "INSERT INTO document_artifacts (tenant_id, document_id, kind, sha256, content)"
				+ " VALUES (?, ?, ?, ?, ?)";
		byte[] content = "x".getBytes(StandardCharsets.UTF_8);

		jdbc.update(insert, s.tenant(), document, "XML_AUTHORIZED", ArtifactStore.sha256(content), content);
		jdbc.update(insert, s.tenant(), document, "RIDE_PDF", ArtifactStore.sha256(content), content);

		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_AUTHORIZED)).isPresent();
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.RIDE_PDF)).isPresent();
		assertThatThrownBy(
				() -> jdbc.update(insert, s.tenant(), document, "RIDE_HTML", ArtifactStore.sha256(content), content))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
