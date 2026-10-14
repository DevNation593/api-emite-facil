package com.example.einvoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestData;
import com.jayway.jsonpath.JsonPath;

/**
 * Dos empresas, A y B. Ninguna petición de B puede leer ni modificar nada de A, y la respuesta no
 * distingue un recurso ajeno de uno inexistente.
 */
class TenantIsolationIT extends AbstractIntegrationTest {

	String tokenA;

	String tokenB;

	String rucA;

	UUID taxpayerA;

	@BeforeEach
	void twoTenants() throws Exception {
		tokenA = newUserToken();
		createTenant(tokenA);
		rucA = TestData.ruc();
		taxpayerA = createTaxpayer(tokenA, rucA);
		tokenB = newUserToken();
		createTenant(tokenB);
		createTaxpayer(tokenB, TestData.ruc());
	}

	@Test
	void taxpayerOfAnotherTenantIsNotFound() throws Exception {
		String foreign = notFoundBody(tokenB, "/api/v1/taxpayers/" + taxpayerA);
		String unknown = notFoundBody(tokenB, "/api/v1/taxpayers/" + UUID.randomUUID());
		assertSameProblem(foreign, unknown);
		assertThat(foreign).doesNotContain(rucA);
	}

	@Test
	void listNeverIncludesAnotherTenant() throws Exception {
		mvc.perform(as(get("/api/v1/taxpayers"), tokenB))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[*].ruc", not(hasItem(rucA))));
	}

	@Test
	void cannotCreateEstablishmentUnderForeignTaxpayer() throws Exception {
		createEstablishment(tokenA, taxpayerA, "001");
		String path = "/api/v1/taxpayers/" + taxpayerA + "/establishments";

		mvc.perform(json(post(path), tokenB, "{\"code\":\"002\",\"address\":\"Guayaquil\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM establishments WHERE taxpayer_id = ?", Integer.class,
				taxpayerA))
			.isEqualTo(1);
	}

	@Test
	void cannotListEstablishmentsOfForeignTaxpayer() throws Exception {
		createEstablishment(tokenA, taxpayerA, "001");
		String foreign = notFoundBody(tokenB, "/api/v1/taxpayers/" + taxpayerA + "/establishments");
		String unknown = notFoundBody(tokenB, "/api/v1/taxpayers/" + UUID.randomUUID() + "/establishments");
		assertSameProblem(foreign, unknown);
	}

	@Test
	void cannotCreateEmissionPointUnderForeignEstablishment() throws Exception {
		UUID establishmentA = createEstablishment(tokenA, taxpayerA, "001");

		mvc.perform(json(post("/api/v1/emission-points"), tokenB, emissionPointJson(establishmentA, "001")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM emission_points WHERE establishment_id = ?",
				Integer.class, establishmentA))
			.isZero();
	}

	@Test
	void cannotListEmissionPointsOfForeignEstablishment() throws Exception {
		UUID establishmentA = createEstablishment(tokenA, taxpayerA, "001");
		mvc.perform(json(post("/api/v1/emission-points"), tokenA, emissionPointJson(establishmentA, "001")))
			.andExpect(status().isCreated());

		String foreign = notFoundBody(tokenB, "/api/v1/emission-points?establishmentId=" + establishmentA);
		String unknown = notFoundBody(tokenB, "/api/v1/emission-points?establishmentId=" + UUID.randomUUID());
		assertSameProblem(foreign, unknown);
	}

	@Test
	void headerOfForeignTenantDoesNotGrantAccess() throws Exception {
		UUID tenantA = jdbc.queryForObject("SELECT tenant_id FROM taxpayers WHERE id = ?", UUID.class, taxpayerA);
		mvc.perform(as(get("/api/v1/taxpayers/" + taxpayerA), tokenB).header(TENANT_HEADER, tenantA.toString()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("TENANT_FORBIDDEN"));
	}

	@Test
	void cannotIssueWithForeignTaxpayerCustomerOrProduct() throws Exception {
		BillingScenario a = newBillingScenario();
		BillingScenario b = newBillingScenario();
		String ownInvoice = b.invoiceJson("1", "11.50");
		for (String body : new String[] { ownInvoice.replace(b.taxpayer().toString(), a.taxpayer().toString()),
				ownInvoice.replace(b.customer().toString(), a.customer().toString()),
				ownInvoice.replace(b.product().toString(), a.product().toString()) }) {
			mvc.perform(invoiceRequest(b.owner(), body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fiscal_documents WHERE tenant_id IN (?, ?)",
				Integer.class, a.tenant(), b.tenant()))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM document_sequences WHERE tenant_id IN (?, ?)",
				Integer.class, a.tenant(), b.tenant()))
			.isZero();
	}

	@Test
	void documentOfAnotherTenantIsNotFound() throws Exception {
		BillingScenario a = newBillingScenario();
		BillingScenario b = newBillingScenario();
		UUID documentA = issueInvoice(a.owner(), a.invoiceJson("1", "11.50"));

		String foreign = notFoundBody(b.owner(), "/api/v1/documents/" + documentA);
		String unknown = notFoundBody(b.owner(), "/api/v1/documents/" + UUID.randomUUID());
		assertSameProblem(foreign, unknown);
		mvc.perform(as(get("/api/v1/documents"), b.owner()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void customersAndProductsOfAnotherTenantAreNotFound() throws Exception {
		BillingScenario a = newBillingScenario();
		BillingScenario b = newBillingScenario();
		notFoundBody(b.owner(), "/api/v1/customers/" + a.customer());
		notFoundBody(b.owner(), "/api/v1/products/" + a.product());
	}

	String notFoundBody(String token, String path) throws Exception {
		return mvc.perform(as(get(path), token))
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	static void assertSameProblem(String first, String second) {
		for (String field : new String[] { "$.errorCode", "$.detail", "$.title", "$.status" }) {
			assertThat(JsonPath.<Object>read(first, field)).isEqualTo(JsonPath.<Object>read(second, field));
		}
		assertThat(JsonPath.<String>read(first, "$.errorCode")).isEqualTo("NOT_FOUND");
	}

}
