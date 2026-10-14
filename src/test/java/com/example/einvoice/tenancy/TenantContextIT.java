package com.example.einvoice.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;
import com.jayway.jsonpath.JsonPath;

class TenantContextIT extends AbstractIntegrationTest {

	static final String CURRENT = "/api/v1/tenants/current";

	@Test
	void singleMembershipNeedsNoHeader() throws Exception {
		String token = newUserToken();
		UUID tenant = createTenant(token);
		mvc.perform(as(get(CURRENT), token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(tenant.toString()))
			.andExpect(jsonPath("$.role").value("OWNER"));
	}

	@Test
	void severalMembershipsRequireHeader() throws Exception {
		String token = newUserToken();
		createTenant(token);
		UUID second = createTenant(token);
		mvc.perform(as(get(CURRENT), token))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("TENANT_REQUIRED"));
		mvc.perform(as(get(CURRENT), token).header(TENANT_HEADER, second.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(second.toString()));
	}

	@Test
	void userWithoutTenantIsForbidden() throws Exception {
		mvc.perform(as(get(CURRENT), newUserToken()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("TENANT_FORBIDDEN"));
	}

	@Test
	void malformedHeaderIsBadRequest() throws Exception {
		String token = newUserToken();
		createTenant(token);
		mvc.perform(as(get(CURRENT), token).header(TENANT_HEADER, "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("TENANT_HEADER_INVALID"));
	}

	@Test
	void foreignAndUnknownTenantLookTheSame() throws Exception {
		String token = newUserToken();
		createTenant(token);
		UUID foreign = createTenant(newUserToken());

		String foreignBody = forbiddenBody(token, foreign);
		String unknownBody = forbiddenBody(token, UUID.randomUUID());

		assertThat(JsonPath.<String>read(foreignBody, "$.errorCode")).isEqualTo("TENANT_FORBIDDEN");
		for (String field : new String[] { "$.errorCode", "$.detail", "$.title", "$.status" }) {
			assertThat(JsonPath.<Object>read(foreignBody, field)).isEqualTo(JsonPath.<Object>read(unknownBody, field));
		}
	}

	@Test
	void suspendedMembershipIsForbidden() throws Exception {
		UUID tenant = TestData.tenant(jdbc);
		String token = memberToken(tenant, "ADMIN");
		mvc.perform(as(get(CURRENT), token)).andExpect(status().isOk());

		jdbc.update("UPDATE memberships SET status = 'SUSPENDED' WHERE tenant_id = ?", tenant);

		mvc.perform(as(get(CURRENT), token)).andExpect(status().isForbidden());
		mvc.perform(as(get(CURRENT), token).header(TENANT_HEADER, tenant.toString()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("TENANT_FORBIDDEN"));
	}

	@Test
	void suspendedTenantIsForbidden() throws Exception {
		String token = newUserToken();
		UUID tenant = createTenant(token);
		jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", tenant);

		mvc.perform(as(get(CURRENT), token)).andExpect(status().isForbidden());
		mvc.perform(as(get(CURRENT), token).header(TENANT_HEADER, tenant.toString()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("TENANT_FORBIDDEN"));
	}

	@Test
	void roleComesFromMembership() throws Exception {
		UUID tenant = TestData.tenant(jdbc);
		mvc.perform(as(get(CURRENT), memberToken(tenant, "AUDITOR")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("AUDITOR"));
	}

	private String forbiddenBody(String token, UUID tenantId) throws Exception {
		return mvc.perform(as(get(CURRENT), token).header(TENANT_HEADER, tenantId.toString()))
			.andExpect(status().isForbidden())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

}
