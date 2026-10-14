package com.example.einvoice.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

class ProductApiIT extends AbstractIntegrationTest {

	static final String PRODUCTS = "/api/v1/products";

	String owner;

	UUID tenant;

	@BeforeEach
	void newTenant() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
	}

	@Test
	void adminCreatesProduct() throws Exception {
		String admin = memberToken(tenant, "ADMIN");
		String body = mvc.perform(json(post(PRODUCTS), admin, productJson("SRV-001", "10.5", "4")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sku").value("SRV-001"))
			.andExpect(jsonPath("$.name").value("Servicio de consultoría"))
			.andExpect(jsonPath("$.unitPrice").value("10.500000"))
			.andExpect(jsonPath("$.ivaRateCode").value("4"))
			.andExpect(jsonPath("$.tenantId").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

		mvc.perform(as(get(PRODUCTS + "/" + id), admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unitPrice").value("10.500000"));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'PRODUCT_CREATED' AND entity_id = ? AND tenant_id = ?",
				Integer.class, id, tenant))
			.isEqualTo(1);
	}

	@Test
	void billerCannotCreateProduct() throws Exception {
		String biller = memberToken(tenant, "BILLER");
		mvc.perform(json(post(PRODUCTS), biller, productJson("SRV-001", "10.00", "4")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
		mvc.perform(as(get(PRODUCTS), biller)).andExpect(status().isOk());
	}

	@Test
	void keepsSixDecimalsAndZeroPrice() throws Exception {
		mvc.perform(json(post(PRODUCTS), owner, productJson("P-1", "0.000001", "0")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.unitPrice").value("0.000001"));
		mvc.perform(json(post(PRODUCTS), owner, productJson("P-2", "0", "0")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.unitPrice").value("0.000000"));
		mvc.perform(json(post(PRODUCTS), owner, productJson("P-3", "999999999999.999999", "4")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.unitPrice").value("999999999999.999999"));
	}

	@Test
	void rejectsInvalidPrice() throws Exception {
		for (String price : new String[] { "-1", "1.1234567", "1e3", "1,5", "", " 1", "1234567890123" }) {
			mvc.perform(json(post(PRODUCTS), owner, productJson("P-X", price, "4")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("unitPrice"));
		}
		mvc.perform(json(post(PRODUCTS), owner, "{\"sku\":\"P-X\",\"name\":\"X\",\"ivaRateCode\":\"4\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("unitPrice"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ?", Integer.class, tenant))
			.isZero();
	}

	@Test
	void rejectsInvalidSkuAndName() throws Exception {
		for (String sku : new String[] { "", "con espacio", "x".repeat(26), "ñandú" }) {
			mvc.perform(json(post(PRODUCTS), owner, productJson(sku, "1.00", "4")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("sku"));
		}
		mvc.perform(json(post(PRODUCTS), owner,
				productJson("P-1", "1.00", "4").replace("Servicio de consultoría", " ")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void rejectsUnknownIvaRate() throws Exception {
		mvc.perform(json(post(PRODUCTS), owner, productJson("P-1", "1.00", "9")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("TAX_RATE_UNKNOWN"));
	}

	@Test
	void duplicateSkuIsConflict() throws Exception {
		createProduct(owner, "P-1", "1.00", "4");
		mvc.perform(json(post(PRODUCTS), owner, productJson("P-1", "2.00", "0")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("PRODUCT_SKU_TAKEN"));
		String other = newUserToken();
		createTenant(other);
		createProduct(other, "P-1", "1.00", "4");
	}

	@Test
	void searchesAndPaginates() throws Exception {
		for (int i = 0; i < 23; i++) {
			createProduct(owner, (i < 2 ? "ZZ-" : "P-") + i, "1.00", "4");
		}
		mvc.perform(as(get(PRODUCTS).param("size", "10"), owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(10)))
			.andExpect(jsonPath("$.total").value(23));
		mvc.perform(as(get(PRODUCTS).param("q", "zz-"), owner)).andExpect(jsonPath("$.total").value(2));
		mvc.perform(as(get(PRODUCTS).param("q", "CONSULTOR"), owner)).andExpect(jsonPath("$.total").value(23));
		mvc.perform(as(get(PRODUCTS).param("q", "_"), owner)).andExpect(jsonPath("$.total").value(0));
		mvc.perform(as(get(PRODUCTS).param("size", "101"), owner)).andExpect(status().isBadRequest());
	}

	@Test
	void productOfAnotherTenantIsNotFound() throws Exception {
		UUID product = createProduct(owner, "P-1", "1.00", "4");
		String other = newUserToken();
		createTenant(other);
		mvc.perform(as(get(PRODUCTS + "/" + product), other))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
		mvc.perform(as(get(PRODUCTS), other)).andExpect(jsonPath("$.total").value(0));
	}

}
