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

class EstablishmentApiIT extends AbstractIntegrationTest {

	String owner;

	UUID tenant;

	UUID taxpayer;

	@BeforeEach
	void taxpayerOfNewTenant() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
		taxpayer = createTaxpayer(owner, TestData.ruc());
	}

	String path(UUID taxpayerId) {
		return "/api/v1/taxpayers/" + taxpayerId + "/establishments";
	}

	static String body(String code) {
		return "{\"code\":\"" + code + "\",\"address\":\"Av. 10 de Agosto, Quito\"}";
	}

	@Test
	void createsAndListsEstablishments() throws Exception {
		mvc.perform(json(post(path(taxpayer)), owner, body("002")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNotEmpty())
			.andExpect(jsonPath("$.taxpayerId").value(taxpayer.toString()))
			.andExpect(jsonPath("$.code").value("002"))
			.andExpect(jsonPath("$.address").value("Av. 10 de Agosto, Quito"));
		UUID first = createEstablishment(owner, taxpayer, "001");

		mvc.perform(as(get(path(taxpayer)), owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].code", contains("001", "002")));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'ESTABLISHMENT_CREATED' AND entity_id = ? AND tenant_id = ?",
				Integer.class, first, tenant))
			.isEqualTo(1);
	}

	@Test
	void duplicateCodeIsConflict() throws Exception {
		createEstablishment(owner, taxpayer, "001");
		mvc.perform(json(post(path(taxpayer)), owner, body("001")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("ESTABLISHMENT_CODE_TAKEN"));
	}

	@Test
	void sameCodeUnderAnotherTaxpayerIsAllowed() throws Exception {
		createEstablishment(owner, taxpayer, "001");
		UUID other = createTaxpayer(owner, TestData.ruc());
		mvc.perform(json(post(path(other)), owner, body("001"))).andExpect(status().isCreated());
	}

	@Test
	void rejectsInvalidCode() throws Exception {
		for (String code : new String[] { "1", "0001", "A01", "" }) {
			mvc.perform(json(post(path(taxpayer)), owner, body(code)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("code"));
		}
		mvc.perform(json(post(path(taxpayer)), owner, "{\"code\":\"001\",\"address\":\"Quito\\u0000Norte\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("address"));
		mvc.perform(json(post(path(taxpayer)), owner, "{\"code\":\"001\",\"address\":\" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("address"));
	}

	@Test
	void readOnlyRoleCannotCreate() throws Exception {
		createEstablishment(owner, taxpayer, "001");
		String biller = memberToken(tenant, "BILLER");
		mvc.perform(json(post(path(taxpayer)), biller, body("002")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
		mvc.perform(as(get(path(taxpayer)), biller))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void unknownTaxpayerIsNotFound() throws Exception {
		UUID unknown = UUID.randomUUID();
		mvc.perform(json(post(path(unknown)), owner, body("001")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
		mvc.perform(as(get(path(unknown)), owner)).andExpect(status().isNotFound());
		mvc.perform(as(get("/api/v1/taxpayers/no-es-uuid/establishments"), owner))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
	}

}
