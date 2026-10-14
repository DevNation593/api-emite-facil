package com.example.einvoice.sri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SriSoapSamples;
import com.example.einvoice.support.SriSoapSamples.Msg;
import com.example.einvoice.support.SriStub;
import com.example.einvoice.support.SriStub.Reply;
import com.example.einvoice.support.TestCertificates;

/** Consulta de los intentos ante el SRI y de los datos de autorización de un comprobante. */
class SriAttemptApiIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

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

	@Test
	void attemptsAreListedNewestFirstWithTheirMessages() throws Exception {
		sri.onAuthorization(key, Reply.xml(SriSoapSamples.notAuthorized(key, "NO AUTORIZADO",
				new Msg("39", "FIRMA INVALIDA", "La firma no corresponde al emisor", "ERROR"))));
		pipeline.advance(s.tenant(), document);

		mvc.perform(as(get("/api/v1/documents/" + document + "/sri-attempts"), s.owner()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].operation").value("AUTHORIZATION"))
			.andExpect(jsonPath("$[0].outcome").value("NOT_AUTHORIZED"))
			.andExpect(jsonPath("$[0].sriStatus").value("NO AUTORIZADO"))
			.andExpect(jsonPath("$[0].httpStatus").value(200))
			.andExpect(jsonPath("$[0].startedAt").isNotEmpty())
			.andExpect(jsonPath("$[0].completedAt").isNotEmpty())
			.andExpect(jsonPath("$[0].messages.length()").value(1))
			.andExpect(jsonPath("$[0].messages[0].identifier").value("39"))
			.andExpect(jsonPath("$[0].messages[0].message").value("FIRMA INVALIDA"))
			.andExpect(jsonPath("$[0].messages[0].additionalInfo").value("La firma no corresponde al emisor"))
			.andExpect(jsonPath("$[0].messages[0].type").value("ERROR"))
			.andExpect(jsonPath("$[1].operation").value("RECEPTION"))
			.andExpect(jsonPath("$[1].outcome").value("RECEIVED"))
			.andExpect(jsonPath("$[1].sriStatus").value("RECIBIDA"))
			.andExpect(jsonPath("$[1].messages.length()").value(0));
	}

	@Test
	void rawResponseIsNeverExposed() throws Exception {
		sri.onReception(key, Reply.xml(SriSoapSamples.returned(key, Msg.error("35", "DOCUMENTO INVÁLIDO"))));
		pipeline.advance(s.tenant(), document);

		String body = mvc.perform(as(get("/api/v1/documents/" + document + "/sri-attempts"), s.owner()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].outcome").value("RETURNED"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContain("response", "Envelope", "errorDetail");
	}

	@Test
	void documentWithoutAttemptsHasAnEmptyList() throws Exception {
		mvc.perform(as(get("/api/v1/documents/" + document + "/sri-attempts"), memberToken(s.tenant(), "AUDITOR")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void foreignDocumentIsNotFound() throws Exception {
		pipeline.advance(s.tenant(), document);
		BillingScenario other = newBillingScenario();

		mvc.perform(as(get("/api/v1/documents/" + document + "/sri-attempts"), other.owner()))
			.andExpect(status().isNotFound());
		mvc.perform(as(get("/api/v1/documents/" + UUID.randomUUID() + "/sri-attempts"), s.owner()))
			.andExpect(status().isNotFound());
	}

	@Test
	void documentShowsItsAuthorization() throws Exception {
		mvc.perform(as(get("/api/v1/documents/" + document), s.owner()))
			.andExpect(jsonPath("$.authorizationNumber").doesNotExist())
			.andExpect(jsonPath("$.authorizedAt").doesNotExist());

		pipeline.advance(s.tenant(), document);

		mvc.perform(as(get("/api/v1/documents/" + document), s.owner()))
			.andExpect(jsonPath("$.state").value("AUTHORIZED"))
			.andExpect(jsonPath("$.authorizationNumber").value(key))
			.andExpect(jsonPath("$.authorizedAt").value("2012-03-05T21:57:34.997Z"));
	}

}
