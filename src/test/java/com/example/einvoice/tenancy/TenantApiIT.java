package com.example.einvoice.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestTokens;
import com.jayway.jsonpath.JsonPath;

class TenantApiIT extends AbstractIntegrationTest {

	@Test
	void createsTenantWithCallerAsOwner() throws Exception {
		String token = newUserToken();
		String body = mvc.perform(json(post("/api/v1/tenants"), token, "{\"name\":\"  Comercial Andina  \"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Comercial Andina"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.role").value("OWNER"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID tenantId = UUID.fromString(JsonPath.read(body, "$.id"));

		assertThat(jdbc.queryForObject("SELECT role FROM memberships WHERE tenant_id = ?", String.class, tenantId))
			.isEqualTo("OWNER");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'TENANT_CREATED' AND entity_id = ? AND tenant_id = ?",
				Integer.class, tenantId, tenantId))
			.isEqualTo(1);
	}

	@Test
	void listsOnlyCallerTenants() throws Exception {
		String tokenA = newUserToken();
		UUID first = createTenant(tokenA);
		UUID second = createTenant(tokenA);
		UUID foreign = createTenant(newUserToken());

		mvc.perform(as(get("/api/v1/tenants"), tokenA))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[*].id", containsInAnyOrder(first.toString(), second.toString())))
			.andExpect(jsonPath("$[*].id", not(hasItem(foreign.toString()))))
			.andExpect(jsonPath("$[0].role").value("OWNER"));
	}

	@Test
	void sameSubjectIsOneUser() throws Exception {
		String subject = "u-" + UUID.randomUUID();
		mvc.perform(as(get("/api/v1/tenants"), TestTokens.bearer(subject))).andExpect(status().isOk());
		mvc.perform(as(get("/api/v1/tenants"), TestTokens.bearer(subject, "nuevo@example.test")))
			.andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE subject = ?", Integer.class, subject))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT email FROM users WHERE subject = ?", String.class, subject))
			.isEqualTo("nuevo@example.test");
	}

	@Test
	void rejectsBlankOrTooLongName() throws Exception {
		String token = newUserToken();
		for (String name : new String[] { "", "   ", "x".repeat(201) }) {
			mvc.perform(json(post("/api/v1/tenants"), token, "{\"name\":\"" + name + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("name"));
		}
		mvc.perform(json(post("/api/v1/tenants"), token, "{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
	}

	@Test
	void rejectsControlCharactersInName() throws Exception {
		mvc.perform(json(post("/api/v1/tenants"), newUserToken(), "{\"name\":\"a\\u0000b\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void rejectsMalformedJson() throws Exception {
		mvc.perform(json(post("/api/v1/tenants"), newUserToken(), "{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
	}

	@Test
	void ignoresTenantIdInBody() throws Exception {
		UUID supplied = UUID.randomUUID();
		String body = mvc
			.perform(json(post("/api/v1/tenants"), newUserToken(),
					"{\"name\":\"Acme\",\"id\":\"" + supplied + "\",\"tenantId\":\"" + supplied + "\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.id")).isNotEqualTo(supplied.toString());
	}

}
