package com.example.einvoice.taxpayer;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;
import com.jayway.jsonpath.JsonPath;

/** Datos del emisor que van al XML como leyendas (ficha v2.34, Anexo 3 y Anexos 21 y 22). */
class TaxpayerFiscalDataIT extends AbstractIntegrationTest {

	static final String TAXPAYERS = "/api/v1/taxpayers";

	String owner;

	@BeforeEach
	void newTenant() throws Exception {
		owner = newUserToken();
		createTenant(owner);
	}

	static String withFiscalData(String ruc, String extra) {
		String base = taxpayerJson(ruc);
		return base.substring(0, base.length() - 1) + "," + extra + "}";
	}

	@Test
	void defaultsWhenOmitted() throws Exception {
		mvc.perform(json(post(TAXPAYERS), owner, taxpayerJson(TestData.ruc())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.accountingRequired").value(false))
			.andExpect(jsonPath("$.specialTaxpayerCode").doesNotExist())
			.andExpect(jsonPath("$.withholdingAgentCode").doesNotExist())
			.andExpect(jsonPath("$.rimpeRegime").value("NONE"));
	}

	@Test
	void acceptsAndReturnsFiscalData() throws Exception {
		String body = mvc
			.perform(json(post(TAXPAYERS), owner,
					withFiscalData(TestData.ruc(), "\"accountingRequired\":true,\"specialTaxpayerCode\":\"5368\","
							+ "\"withholdingAgentCode\":\"1\",\"rimpeRegime\":\"EMPRENDEDOR\"")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.accountingRequired").value(true))
			.andExpect(jsonPath("$.specialTaxpayerCode").value("5368"))
			.andExpect(jsonPath("$.withholdingAgentCode").value("1"))
			.andExpect(jsonPath("$.rimpeRegime").value("EMPRENDEDOR"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonPath.read(body, "$.id");
		mvc.perform(as(get(TAXPAYERS + "/" + id), owner))
			.andExpect(jsonPath("$.specialTaxpayerCode").value("5368"))
			.andExpect(jsonPath("$.rimpeRegime").value("EMPRENDEDOR"));
		mvc.perform(json(post(TAXPAYERS), owner,
				withFiscalData(TestData.ruc(), "\"withholdingAgentCode\":\"12345678\"")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.withholdingAgentCode").value("12345678"));
	}

	@Test
	void negocioPopularIsRejectedUntilTheXsdAllowsItsLegend() throws Exception {
		// El XSD de factura 1.1.0 del repositorio no admite la leyenda de negocio popular: toda factura
		// de ese emisor saldría inválida después de consumir su secuencial.
		mvc.perform(json(post(TAXPAYERS), owner, withFiscalData(TestData.ruc(), "\"rimpeRegime\":\"NEGOCIO_POPULAR\"")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("RIMPE_REGIME_UNSUPPORTED"));
	}

	@Test
	void rejectsInvalidFiscalData() throws Exception {
		String[][] invalid = { { "specialTaxpayerCode", "\"specialTaxpayerCode\":\"12\"" },
				{ "specialTaxpayerCode", "\"specialTaxpayerCode\":\"12-34\"" },
				{ "specialTaxpayerCode", "\"specialTaxpayerCode\":\"12345678901234\"" },
				{ "withholdingAgentCode", "\"withholdingAgentCode\":\"01\"" },
				{ "withholdingAgentCode", "\"withholdingAgentCode\":\"12A\"" },
				{ "withholdingAgentCode", "\"withholdingAgentCode\":\"123456789\"" } };
		for (String[] pair : invalid) {
			mvc.perform(json(post(TAXPAYERS), owner, withFiscalData(TestData.ruc(), pair[1])))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[*].field", hasItem(pair[0])));
		}
		mvc.perform(json(post(TAXPAYERS), owner, withFiscalData(TestData.ruc(), "\"rimpeRegime\":\"OTRO\"")))
			.andExpect(status().isBadRequest());
	}

}
