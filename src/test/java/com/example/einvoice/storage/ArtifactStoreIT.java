package com.example.einvoice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.einvoice.storage.ArtifactStore.StoredArtifact;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;

class ArtifactStoreIT extends AbstractIntegrationTest {

	static final byte[] XML = "<factura id=\"comprobante\">ñ</factura>".getBytes(StandardCharsets.UTF_8);

	@Autowired
	ArtifactStore artifacts;

	@Autowired
	PlatformTransactionManager transactionManager;

	BillingScenario s;

	UUID document;

	@BeforeEach
	void issuedInvoice() throws Exception {
		s = newBillingScenario();
		document = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
	}

	String save(ArtifactKind kind, byte[] content) {
		return new TransactionTemplate(transactionManager)
			.execute(status -> artifacts.save(s.tenant(), document, kind, content));
	}

	static String path(UUID documentId, String kind) {
		return "/api/v1/documents/" + documentId + "/artifacts/" + kind;
	}

	@Test
	void storesAndReturnsExactBytes() throws Exception {
		String sha = save(ArtifactKind.XML_SIGNED, XML);

		StoredArtifact stored = artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED).orElseThrow();
		assertThat(stored.content()).isEqualTo(XML);
		assertThat(stored.sha256()).isEqualTo(sha)
			.isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(XML)));
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_UNSIGNED)).isEmpty();
	}

	@Test
	void secondSaveOfSameKindFails() {
		save(ArtifactKind.XML_SIGNED, XML);
		assertThatThrownBy(() -> save(ArtifactKind.XML_SIGNED, "<otro/>".getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED).orElseThrow().content())
			.isEqualTo(XML);
		save(ArtifactKind.XML_UNSIGNED, XML);
	}

	@Test
	void saveRequiresATransactionAndADocumentOfTheTenant() {
		assertThatThrownBy(() -> artifacts.save(s.tenant(), document, ArtifactKind.XML_SIGNED, XML))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
			.execute(status -> artifacts.save(UUID.randomUUID(), document, ArtifactKind.XML_SIGNED, XML)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void artifactOfAnotherTenantIsNotFound() throws Exception {
		save(ArtifactKind.XML_SIGNED, XML);
		BillingScenario other = newBillingScenario();

		assertThat(artifacts.find(other.tenant(), document, ArtifactKind.XML_SIGNED)).isEmpty();
		mvc.perform(as(get(path(document, "xml-signed")), other.owner()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

	@Test
	void downloadReturnsXmlWithEtag() throws Exception {
		String sha = save(ArtifactKind.XML_SIGNED, XML);
		mvc.perform(as(get(path(document, "xml-signed")), memberToken(s.tenant(), "AUDITOR")))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
			.andExpect(header().string("ETag", "\"" + sha + "\""))
			.andExpect(content().bytes(XML));
	}

	@Test
	void unknownKindIsBadRequest() throws Exception {
		mvc.perform(as(get(path(document, "pdf")), s.owner()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
	}

	@Test
	void missingArtifactIsNotFound() throws Exception {
		mvc.perform(as(get(path(document, "xml-signed")), s.owner())).andExpect(status().isNotFound());
		mvc.perform(as(get(path(UUID.randomUUID(), "xml-signed")), s.owner())).andExpect(status().isNotFound());
	}

}
