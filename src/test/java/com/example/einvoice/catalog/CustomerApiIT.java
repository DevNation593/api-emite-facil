package com.example.einvoice.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

class CustomerApiIT extends AbstractIntegrationTest {

	static final String CUSTOMERS = "/api/v1/customers";

	String owner;

	UUID tenant;

	@BeforeEach
	void newTenant() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
	}

	@Test
	void billerCreatesCustomer() throws Exception {
		String biller = memberToken(tenant, "BILLER");
		String body = mvc.perform(json(post(CUSTOMERS), biller, customerJson("05", "1712345678", "María Pérez")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.idType").value("05"))
			.andExpect(jsonPath("$.idNumber").value("1712345678"))
			.andExpect(jsonPath("$.name").value("María Pérez"))
			.andExpect(jsonPath("$.email").value("cliente@example.test"))
			.andExpect(jsonPath("$.tenantId").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonPath.read(body, "$.id");

		mvc.perform(as(get(CUSTOMERS + "/" + id), biller))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.address").value("Av. República, Quito"));
	}

	@Test
	void auditorCannotCreate() throws Exception {
		String auditor = memberToken(tenant, "AUDITOR");
		mvc.perform(json(post(CUSTOMERS), auditor, customerJson("05", "1712345678", "María Pérez")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
		mvc.perform(as(get(CUSTOMERS), auditor)).andExpect(status().isOk());
	}

	@Test
	void rejectsUnknownIdentificationType() throws Exception {
		mvc.perform(json(post(CUSTOMERS), owner, customerJson("09", "1712345678", "María Pérez")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("IDENTIFICATION_TYPE_UNKNOWN"));
	}

	@Test
	void validatesIdentificationByType() throws Exception {
		String[][] invalid = { { "04", "179001234500" }, { "04", "17900123450012" }, { "05", "171234567" },
				{ "05", "17123456789" }, { "07", "9999999999998" }, { "07", "1712345678" }, { "06", "AB" },
				{ "06", "AB-123" }, { "08", "X".repeat(21) } };
		for (String[] pair : invalid) {
			mvc.perform(json(post(CUSTOMERS), owner, customerJson(pair[0], pair[1], "María Pérez")))
				.andExpect(result -> assertThat(result.getResponse().getStatus())
					.as("%s %s", pair[0], pair[1])
					.isIn(400, 422));
		}
		String[][] valid = { { "04", "1790012345001" }, { "05", "1712345678" }, { "06", "AB123456" },
				{ "07", "9999999999999" }, { "08", "X1234567890" } };
		for (String[] pair : valid) {
			mvc.perform(json(post(CUSTOMERS), owner, customerJson(pair[0], pair[1], "María Pérez")))
				.andExpect(status().isCreated());
		}
		mvc.perform(json(post(CUSTOMERS), owner, customerJson("05", "171234567", "María Pérez")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("IDENTIFICATION_INVALID"));
	}

	@Test
	void finalConsumerNumberIsReservedForItsType() throws Exception {
		for (String type : new String[] { "04", "06", "08" }) {
			mvc.perform(json(post(CUSTOMERS), owner, customerJson(type, "9999999999999", "Sin identificar")))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errorCode").value("IDENTIFICATION_INVALID"));
		}
		mvc.perform(json(post(CUSTOMERS), owner, customerJson("07", "9999999999999", "CONSUMIDOR FINAL")))
			.andExpect(status().isCreated());
	}

	@Test
	void rejectsUnusableSearchAndPagingParameters() throws Exception {
		String nul = String.valueOf((char) 0);
		mvc.perform(as(get(CUSTOMERS).param("q", "a" + nul + "b"), owner))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
		mvc.perform(as(get(CUSTOMERS).param("q", "x".repeat(101)), owner)).andExpect(status().isBadRequest());
		mvc.perform(as(get(CUSTOMERS).param("page", "2147483647").param("size", "100"), owner))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
		mvc.perform(as(get("/api/v1/products").param("page", "30000000").param("size", "100"), owner))
			.andExpect(status().isBadRequest());
		mvc.perform(as(get("/api/v1/products").param("q", nul), owner)).andExpect(status().isBadRequest());
		mvc.perform(as(get("/api/v1/documents").param("page", "2147483647").param("size", "100"), owner))
			.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsInvalidFields() throws Exception {
		mvc.perform(json(post(CUSTOMERS), owner, customerJson("05", "1712345678", "")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		mvc.perform(json(post(CUSTOMERS), owner,
				customerJson("05", "1712345678", "María").replace("cliente@example.test", "no-es-correo")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("email"));
	}

	@Test
	void duplicateIdentificationIsConflict() throws Exception {
		createCustomer(owner, "05", "1712345678");
		mvc.perform(json(post(CUSTOMERS), owner, customerJson("05", "1712345678", "Otro Nombre")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("CUSTOMER_ALREADY_EXISTS"));
	}

	@Test
	void sameIdentificationInAnotherTenantIsAllowed() throws Exception {
		createCustomer(owner, "05", "1712345678");
		String other = newUserToken();
		createTenant(other);
		createCustomer(other, "05", "1712345678");
	}

	@Test
	void searchesAndPaginates() throws Exception {
		for (int i = 0; i < 25; i++) {
			String number = String.format(Locale.ROOT, "17%08d", i);
			String name = i < 3 ? "Zeta Comercial " + i : "Cliente " + i;
			mvc.perform(json(post(CUSTOMERS), owner, customerJson("05", number, name)))
				.andExpect(status().isCreated());
		}
		mvc.perform(as(get(CUSTOMERS), owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(20)))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.total").value(25));
		mvc.perform(as(get(CUSTOMERS).param("page", "1").param("size", "20"), owner))
			.andExpect(jsonPath("$.items", hasSize(5)));
		mvc.perform(as(get(CUSTOMERS).param("q", "zeta"), owner))
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[0].name").value("Zeta Comercial 0"));
		mvc.perform(as(get(CUSTOMERS).param("q", "170000002"), owner))
			.andExpect(jsonPath("$.total").value(5));
		mvc.perform(as(get(CUSTOMERS).param("q", "%"), owner)).andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void rejectsPageSizeOutOfRange() throws Exception {
		for (String[] params : new String[][] { { "0", "0" }, { "0", "101" }, { "-1", "20" } }) {
			mvc.perform(as(get(CUSTOMERS).param("page", params[0]).param("size", params[1]), owner))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
		}
	}

	@Test
	void customerOfAnotherTenantIsNotFound() throws Exception {
		UUID customer = createCustomer(owner, "05", "1712345678");
		String other = newUserToken();
		createTenant(other);
		mvc.perform(as(get(CUSTOMERS + "/" + customer), other))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
		mvc.perform(as(get(CUSTOMERS), other)).andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void auditMetadataHasNoPersonalData() throws Exception {
		UUID customer = createCustomer(owner, "05", "1712345678");
		String metadata = jdbc.queryForObject(
				"SELECT metadata::text FROM audit_events WHERE action = 'CUSTOMER_CREATED' AND entity_id = ?",
				String.class, customer);
		assertThat(metadata).doesNotContain("1712345678", "María", "cliente@example.test");
	}

}
