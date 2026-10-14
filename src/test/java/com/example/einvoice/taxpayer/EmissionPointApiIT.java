package com.example.einvoice.taxpayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;
import com.jayway.jsonpath.JsonPath;

class EmissionPointApiIT extends AbstractIntegrationTest {

	static final String POINTS = "/api/v1/emission-points";

	String owner;

	UUID tenant;

	UUID taxpayer;

	UUID establishment;

	@BeforeEach
	void establishmentOfNewTenant() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
		taxpayer = createTaxpayer(owner, TestData.ruc());
		establishment = createEstablishment(owner, taxpayer, "001");
	}

	@Test
	void createsAndListsEmissionPoints() throws Exception {
		String body = mvc.perform(json(post(POINTS), owner, emissionPointJson(establishment, "002")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.establishmentId").value(establishment.toString()))
			.andExpect(jsonPath("$.code").value("002"))
			.andExpect(jsonPath("$.enabled").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID created = UUID.fromString(JsonPath.read(body, "$.id"));
		mvc.perform(json(post(POINTS), owner, emissionPointJson(establishment, "001")))
			.andExpect(status().isCreated());

		mvc.perform(as(get(POINTS).param("establishmentId", establishment.toString()), owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].code", contains("001", "002")));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'EMISSION_POINT_CREATED' AND entity_id = ? AND tenant_id = ?",
				Integer.class, created, tenant))
			.isEqualTo(1);
	}

	@Test
	void duplicateCodeIsConflict() throws Exception {
		mvc.perform(json(post(POINTS), owner, emissionPointJson(establishment, "001")))
			.andExpect(status().isCreated());
		mvc.perform(json(post(POINTS), owner, emissionPointJson(establishment, "001")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("EMISSION_POINT_CODE_TAKEN"));
	}

	@Test
	void sameCodeUnderAnotherEstablishmentIsAllowed() throws Exception {
		UUID other = createEstablishment(owner, taxpayer, "002");
		mvc.perform(json(post(POINTS), owner, emissionPointJson(establishment, "001")))
			.andExpect(status().isCreated());
		mvc.perform(json(post(POINTS), owner, emissionPointJson(other, "001"))).andExpect(status().isCreated());
	}

	@Test
	void rejectsInvalidCode() throws Exception {
		for (String code : new String[] { "1", "0001", "A01", "" }) {
			mvc.perform(json(post(POINTS), owner, emissionPointJson(establishment, code)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("code"));
		}
		mvc.perform(json(post(POINTS), owner, "{\"code\":\"001\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("establishmentId"));
	}

	@Test
	void readOnlyRoleCannotCreate() throws Exception {
		String auditor = memberToken(tenant, "AUDITOR");
		mvc.perform(json(post(POINTS), auditor, emissionPointJson(establishment, "001")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
		mvc.perform(as(get(POINTS).param("establishmentId", establishment.toString()), auditor))
			.andExpect(status().isOk());
	}

	@Test
	void missingEstablishmentIdIsBadRequest() throws Exception {
		mvc.perform(as(get(POINTS), owner))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
		mvc.perform(as(get(POINTS).param("establishmentId", UUID.randomUUID().toString()), owner))
			.andExpect(status().isNotFound());
	}

}
