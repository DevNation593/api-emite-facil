package com.example.einvoice.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SriStub;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.support.TestData;

/**
 * Un emisor solo llega a producción con evidencia: certificado vigente, un comprobante autorizado
 * por el SRI en pruebas y la declaración de su propietario. Aquí la transmisión a producción está
 * abierta en la plataforma, que es justo cuando la puerta por emisor importa.
 */
@TestPropertySource(properties = "einvoice.sri.enabled-environments=1,2")
class ProductionActivationIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

	final SriStub sri = SriStub.instance();

	static final String DECLARED = "{\"sriProductionAuthorized\":true}";

	String state(UUID document) {
		return jdbc.queryForObject("SELECT state FROM fiscal_documents WHERE id = ?", String.class, document);
	}

	String key(UUID document) {
		return jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class, document);
	}

	/** Lleva el comprobante tan lejos como llegue por sí solo. */
	String process(BillingScenario s, UUID document) {
		String state = state(document);
		for (int step = 0; step < 10; step++) {
			String next = pipeline.advance(s.tenant(), document);
			if (next.equals(state)) {
				break;
			}
			state = next;
		}
		return state(document);
	}

	/** Emisor con certificado vigente y una factura autorizada en el ambiente de pruebas. */
	BillingScenario certified() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		assertThat(process(s, document)).isEqualTo("AUTHORIZED");
		return s;
	}

	ResultActions readiness(String token, UUID taxpayer) throws Exception {
		return mvc.perform(as(get("/api/v1/taxpayers/" + taxpayer + "/production-readiness"), token));
	}

	ResultActions activate(String token, UUID taxpayer, String body) throws Exception {
		return mvc.perform(json(post("/api/v1/taxpayers/" + taxpayer + "/production-activation"), token, body));
	}

	String environment(UUID taxpayer) {
		return jdbc.queryForObject("SELECT default_environment FROM taxpayers WHERE id = ?", String.class, taxpayer);
	}

	int activationEvents(UUID taxpayer) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'TAXPAYER_PRODUCTION_ENABLED' AND entity_id = ?",
				Integer.class, taxpayer);
	}

	@Test
	void aTaxpayerCannotBeCreatedDirectlyInProduction() throws Exception {
		String owner = newUserToken();
		createTenant(owner);

		mvc.perform(json(post("/api/v1/taxpayers"), owner,
				taxpayerJson(TestData.ruc()).replace("\"defaultEnvironment\":\"1\"", "\"defaultEnvironment\":\"2\"")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("PRODUCTION_NOT_ENABLED"));
	}

	@Test
	void aNewTaxpayerIsMissingEverything() throws Exception {
		BillingScenario s = newBillingScenario();

		readiness(s.owner(), s.taxpayer()).andExpect(status().isOk())
			.andExpect(jsonPath("$.missing", containsInAnyOrder("CERTIFICATE", "TEST_AUTHORIZATION")))
			.andExpect(jsonPath("$.certificateValidUntil", nullValue()))
			.andExpect(jsonPath("$.authorizedTestDocuments").value(0))
			.andExpect(jsonPath("$.transmissionOpen").value(true))
			.andExpect(jsonPath("$.activatedAt", nullValue()));

		activate(s.owner(), s.taxpayer(), DECLARED).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("PRODUCTION_REQUIREMENTS_NOT_MET"))
			.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("certificate", "testAuthorization")));
		assertThat(environment(s.taxpayer())).isEqualTo("1");
	}

	@Test
	void theOwnerMustDeclareThatTheSriEnabledProduction() throws Exception {
		BillingScenario s = certified();
		readiness(s.owner(), s.taxpayer()).andExpect(jsonPath("$.missing", empty()))
			.andExpect(jsonPath("$.certificateValidUntil", notNullValue()))
			.andExpect(jsonPath("$.authorizedTestDocuments").value(1));

		for (String body : new String[] { "{}", "{\"sriProductionAuthorized\":false}" }) {
			activate(s.owner(), s.taxpayer(), body).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errorCode").value("PRODUCTION_REQUIREMENTS_NOT_MET"))
				.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("sriProductionAuthorized")));
		}
		assertThat(environment(s.taxpayer())).isEqualTo("1");
	}

	@Test
	void withEvidenceAndDeclarationTheTaxpayerMovesToProduction() throws Exception {
		BillingScenario s = certified();

		activate(s.owner(), s.taxpayer(), DECLARED).andExpect(status().isOk())
			.andExpect(jsonPath("$.activatedAt", notNullValue()))
			.andExpect(jsonPath("$.missing", empty()));

		assertThat(environment(s.taxpayer())).isEqualTo("2");
		assertThat(activationEvents(s.taxpayer())).isEqualTo(1);
		mvc.perform(as(get("/api/v1/taxpayers/" + s.taxpayer()), s.owner()))
			.andExpect(jsonPath("$.defaultEnvironment").value("2"))
			.andExpect(jsonPath("$.productionEnabledAt", notNullValue()));

		// Las facturas siguientes son de producción, con su propia numeración.
		UUID next = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		assertThat(jdbc.queryForObject("SELECT environment FROM fiscal_documents WHERE id = ?", String.class, next))
			.isEqualTo("2");
		assertThat(key(next).charAt(23)).isEqualTo('2');

		// Activar otra vez no cambia nada ni deja un segundo evento.
		activate(s.owner(), s.taxpayer(), DECLARED).andExpect(status().isOk());
		assertThat(activationEvents(s.taxpayer())).isEqualTo(1);
	}

	@Test
	void onlyTheOwnerOfThatCompanyCanActivate() throws Exception {
		BillingScenario s = certified();

		activate(memberToken(s.tenant(), "ADMIN"), s.taxpayer(), DECLARED).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));

		String stranger = newUserToken();
		createTenant(stranger);
		activate(stranger, s.taxpayer(), DECLARED).andExpect(status().isNotFound());
		readiness(stranger, s.taxpayer()).andExpect(status().isNotFound());

		assertThat(environment(s.taxpayer())).isEqualTo("1");
		assertThat(activationEvents(s.taxpayer())).isZero();
	}

	@Test
	void anExpiredCertificateDoesNotCount() throws Exception {
		BillingScenario s = certified();
		jdbc.update("UPDATE certificates SET valid_to = now() - interval '1 day' WHERE taxpayer_id = ?", s.taxpayer());

		readiness(s.owner(), s.taxpayer()).andExpect(jsonPath("$.missing", containsInAnyOrder("CERTIFICATE")))
			.andExpect(jsonPath("$.certificateValidUntil", nullValue()));
		activate(s.owner(), s.taxpayer(), DECLARED).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("certificate")));
	}

	@Test
	void aDocumentThatNeverLeftTheTestEnvironmentAuthorizedDoesNotCount() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		// Firmada, pero el SRI no la ha autorizado.
		issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));

		readiness(s.owner(), s.taxpayer())
			.andExpect(jsonPath("$.missing", containsInAnyOrder("TEST_AUTHORIZATION")));
	}

	@Test
	void theDatabaseRefusesProductionWithoutActivation() throws Exception {
		BillingScenario s = newBillingScenario();

		assertThatThrownBy(
				() -> jdbc.update("UPDATE taxpayers SET default_environment = '2' WHERE id = ?", s.taxpayer()))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("taxpayers_production_needs_activation");
	}

	@Test
	void aProductionDocumentOfANonActivatedTaxpayerIsNotTransmitted() throws Exception {
		BillingScenario s = certified();
		activate(s.owner(), s.taxpayer(), DECLARED).andExpect(status().isOk());
		UUID document = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		// La activación desaparece (un respaldo antiguo, una corrección manual): el comprobante de
		// producción ya existe, pero su emisor no está habilitado.
		jdbc.update("UPDATE taxpayers SET default_environment = '1', production_enabled_at = NULL,"
				+ " production_enabled_by = NULL WHERE id = ?", s.taxpayer());

		assertThat(process(s, document)).isEqualTo("SIGNED");
		assertThat(sri.receptionCalls(key(document))).isZero();

		// Con el emisor habilitado de nuevo, el mismo comprobante sí sale: era esa puerta la que lo detenía.
		activate(s.owner(), s.taxpayer(), DECLARED).andExpect(status().isOk());
		process(s, document);
		assertThat(sri.receptionCalls(key(document))).isEqualTo(1);
	}

}
