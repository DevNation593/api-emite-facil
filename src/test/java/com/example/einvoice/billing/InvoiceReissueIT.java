package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SriSoapSamples;
import com.example.einvoice.support.SriSoapSamples.Msg;
import com.example.einvoice.support.SriStub;
import com.example.einvoice.support.SriStub.Reply;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.support.TestData;
import com.jayway.jsonpath.JsonPath;

/**
 * Un comprobante que el SRI devolvió o no autorizó no se corrige: se emite otro que lo reemplaza,
 * con su propio número y su propia clave, y ambos quedan enlazados.
 */
class InvoiceReissueIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

	final SriStub sri = SriStub.instance();

	BillingScenario s;

	@BeforeEach
	void anIssuerWithCertificate() throws Exception {
		s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
	}

	String key(UUID document) {
		return jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class, document);
	}

	String state(UUID document) {
		return jdbc.queryForObject("SELECT state FROM fiscal_documents WHERE id = ?", String.class, document);
	}

	UUID rejected(String state) throws Exception {
		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		if ("RETURNED".equals(state)) {
			sri.onReception(key(document),
					Reply.xml(SriSoapSamples.returned(key(document), Msg.error("35", "DOCUMENTO INVÁLIDO"))));
		}
		else {
			sri.onAuthorization(key(document),
					Reply.xml(SriSoapSamples.notAuthorized(key(document), "NO AUTORIZADO", Msg.error("39", "FIRMA INVALIDA"))));
		}
		for (int step = 0; step < 10 && !state.equals(state(document)); step++) {
			pipeline.advance(s.tenant(), document);
		}
		assertThat(state(document)).isEqualTo(state);
		return document;
	}

	String replacing(UUID replaced) {
		return s.invoiceJson("2", "23.00").replaceFirst("\\{", "{\"replacesDocumentId\":\"" + replaced + "\",");
	}

	ResultActions reissue(String idempotencyKey, String body) throws Exception {
		return mvc.perform(json(post("/api/v1/invoices"), s.owner(), body).header("Idempotency-Key", idempotencyKey));
	}

	ResultActions document(UUID id) throws Exception {
		return mvc.perform(as(get("/api/v1/documents/" + id), s.owner()));
	}

	@Test
	void aRejectedDocumentIsReplacedByANewOneAndBothSayIt() throws Exception {
		for (String rejection : List.of("RETURNED", "NOT_AUTHORIZED")) {
			UUID replaced = rejected(rejection);
			String oldKey = key(replaced);
			List<String> oldArtifacts = jdbc.queryForList(
					"SELECT kind || ':' || sha256 FROM document_artifacts WHERE document_id = ? ORDER BY kind", String.class,
					replaced);

			String body = reissue(UUID.randomUUID().toString(), replacing(replaced)).andExpect(status().isAccepted())
				.andReturn()
				.getResponse()
				.getContentAsString();
			UUID replacement = UUID.fromString(JsonPath.read(body, "$.id"));

			// Uno nuevo de verdad: otro secuencial y otra clave.
			assertThat(replacement).isNotEqualTo(replaced);
			assertThat(key(replacement)).isNotEqualTo(oldKey);
			document(replacement).andExpect(jsonPath("$.replacesDocumentId").value(replaced.toString()))
				.andExpect(jsonPath("$.replacedByDocumentId", nullValue()));
			document(replaced).andExpect(jsonPath("$.replacedByDocumentId").value(replacement.toString()))
				.andExpect(jsonPath("$.replacesDocumentId", nullValue()))
				.andExpect(jsonPath("$.state").value(rejection));
			// El rechazado no se toca.
			assertThat(key(replaced)).isEqualTo(oldKey);
			assertThat(jdbc.queryForList(
					"SELECT kind || ':' || sha256 FROM document_artifacts WHERE document_id = ? ORDER BY kind", String.class,
					replaced))
				.isEqualTo(oldArtifacts);
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM audit_events WHERE action = 'DOCUMENT_REPLACED' AND entity_id = ?", Integer.class,
					replaced))
				.isEqualTo(1);
		}
	}

	@Test
	void aDocumentWhoseXmlCouldNotBeGeneratedIsReplacedToo() throws Exception {
		UUID broken = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		jdbc.update("UPDATE fiscal_documents SET issuer_legal_name = repeat('x', 301) WHERE id = ?", broken);
		for (int step = 0; step < 10 && !"XSD_INVALID".equals(state(broken)); step++) {
			pipeline.advance(s.tenant(), broken);
		}
		assertThat(state(broken)).isEqualTo("XSD_INVALID");

		// No admite reintento (saldría igual) ni llegó al SRI: sin esto no tendría salida.
		String body = reissue(UUID.randomUUID().toString(), replacing(broken)).andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();

		document(broken).andExpect(jsonPath("$.replacedByDocumentId").value(JsonPath.<String>read(body, "$.id")));
	}

	@Test
	void theReplacementComesFromTheSameIssuer() throws Exception {
		UUID replaced = rejected("RETURNED");
		UUID otherIssuer = createTaxpayer(s.owner(), TestData.ruc());
		createEmissionPoint(s.owner(), createEstablishment(s.owner(), otherIssuer, "001"), "001");

		reissue(UUID.randomUUID().toString(), replacing(replaced).replace(s.taxpayer().toString(), otherIssuer.toString()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("REPLACED_DOCUMENT_OTHER_ISSUER"));

		// El enlace es único y no se deshace: no se gasta en un comprobante de otro RUC.
		reissue(UUID.randomUUID().toString(), replacing(replaced)).andExpect(status().isAccepted());
	}

	@Test
	void theDetailCarriesWhatAReissueNeeds() throws Exception {
		UUID rejected = rejected("RETURNED");

		document(rejected).andExpect(jsonPath("$.customerId").value(s.customer().toString()))
			.andExpect(jsonPath("$.lines[0].productId").value(s.product().toString()))
			.andExpect(jsonPath("$.replacesDocumentId", nullValue()))
			.andExpect(jsonPath("$.replacedByDocumentId", nullValue()));
	}

	@Test
	void onlyRejectedDocumentsAreReplaced() throws Exception {
		UUID signedOnly = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		UUID authorized = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		for (int step = 0; step < 10 && !"AUTHORIZED".equals(state(authorized)); step++) {
			pipeline.advance(s.tenant(), authorized);
		}
		long before = jdbc.queryForObject("SELECT count(*) FROM fiscal_documents WHERE tenant_id = ?", Long.class, s.tenant());

		for (UUID notRejected : List.of(signedOnly, authorized)) {
			reissue(UUID.randomUUID().toString(), replacing(notRejected)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("REPLACED_DOCUMENT_NOT_REJECTED"));
		}
		reissue(UUID.randomUUID().toString(), replacing(UUID.randomUUID())).andExpect(status().isNotFound());

		// Nada se emitió ni se gastó un secuencial.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fiscal_documents WHERE tenant_id = ?", Long.class, s.tenant()))
			.isEqualTo(before);
	}

	@Test
	void aDocumentIsReplacedOnlyOnce() throws Exception {
		UUID replaced = rejected("RETURNED");
		String idempotencyKey = UUID.randomUUID().toString();
		String first = reissue(idempotencyKey, replacing(replaced)).andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();

		// Repetir la misma petición devuelve la misma factura; otra petición distinta, no.
		String again = reissue(idempotencyKey, replacing(replaced)).andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<String>read(again, "$.id")).isEqualTo(JsonPath.<String>read(first, "$.id"));
		reissue(UUID.randomUUID().toString(), replacing(replaced)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("DOCUMENT_ALREADY_REPLACED"));
	}

	@Test
	void aDocumentOfAnotherCompanyCannotBeReplaced() throws Exception {
		UUID replaced = rejected("RETURNED");
		BillingScenario other = newBillingScenario();
		String body = other.invoiceJson("2", "23.00").replaceFirst("\\{", "{\"replacesDocumentId\":\"" + replaced + "\",");

		mvc.perform(json(post("/api/v1/invoices"), other.owner(), body).header("Idempotency-Key", UUID.randomUUID().toString()))
			.andExpect(status().isNotFound());
		document(replaced).andExpect(jsonPath("$.replacedByDocumentId", nullValue()));
	}

}
