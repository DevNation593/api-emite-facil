package com.example.einvoice.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** Los catálogos sembrados son los de la ficha técnica v2.34 (tablas 6, 17 y 24). */
class CatalogIT extends AbstractIntegrationTest {

	@Autowired
	CatalogRepository catalogs;

	@Test
	void identificationTypesMatchFicha() throws Exception {
		mvc.perform(as(get("/api/v1/catalogs/identification-types"), newUserToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].code", contains("04", "05", "06", "07", "08")))
			.andExpect(jsonPath("$[3].name").value("VENTA A CONSUMIDOR FINAL"));
	}

	@Test
	void paymentMethodsMatchFicha() throws Exception {
		mvc.perform(as(get("/api/v1/catalogs/payment-methods"), newUserToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].code", contains("01", "15", "16", "17", "18", "19", "20", "21")))
			.andExpect(jsonPath("$[0].name").value("SIN UTILIZACION DEL SISTEMA FINANCIERO"));
	}

	@Test
	void ivaRatesMatchFicha() throws Exception {
		String body = mvc.perform(as(get("/api/v1/catalogs/tax-rates"), newUserToken()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<Map<String, String>> rates = JsonPath.read(body, "$[?(@.taxCode == '2')]");
		assertThat(rates).extracting(rate -> rate.get("rateCode") + "=" + rate.get("rate"))
			.containsExactly("0=0.00", "2=12.00", "3=14.00", "4=15.00", "5=5.00", "6=0.00", "7=0.00", "8=8.00",
					"10=13.00");
		assertThat(catalogs.ivaRate("4")).contains(new BigDecimal("15.00"));
		assertThat(catalogs.ivaRate("9")).isEmpty();
	}

	@Test
	void catalogsRequireAuthentication() throws Exception {
		mvc.perform(get("/api/v1/catalogs/tax-rates")).andExpect(status().isUnauthorized());
	}

	@Test
	void paymentMethodValidityFollowsDates() {
		assertThat(catalogs.paymentMethodValidOn("16", LocalDate.of(2016, 5, 31))).isFalse();
		assertThat(catalogs.paymentMethodValidOn("16", LocalDate.of(2016, 6, 1))).isTrue();
		assertThat(catalogs.paymentMethodValidOn("01", LocalDate.of(2026, 10, 10))).isTrue();
		assertThat(catalogs.paymentMethodValidOn("99", LocalDate.of(2026, 10, 10))).isFalse();
		assertThat(catalogs.identificationTypeExists("07")).isTrue();
		assertThat(catalogs.identificationTypeExists("09")).isFalse();
	}

}
