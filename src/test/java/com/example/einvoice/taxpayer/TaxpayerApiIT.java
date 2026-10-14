package com.example.einvoice.taxpayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;
import com.jayway.jsonpath.JsonPath;

class TaxpayerApiIT extends AbstractIntegrationTest {

	static final String TAXPAYERS = "/api/v1/taxpayers";

	@Test
	void ownerCreatesTaxpayer() throws Exception {
		String token = newUserToken();
		UUID tenant = createTenant(token);
		String ruc = TestData.ruc();

		String body = mvc.perform(json(post(TAXPAYERS), token, taxpayerJson(ruc)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.ruc").value(ruc))
			.andExpect(jsonPath("$.legalName").value("Comercial Andina S.A."))
			.andExpect(jsonPath("$.tradeName").value("Andina"))
			.andExpect(jsonPath("$.headOfficeAddress").value("Av. Amazonas N34-12, Quito"))
			.andExpect(jsonPath("$.defaultEnvironment").value("1"))
			.andExpect(jsonPath("$.tenantId").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonPath.read(body, "$.id");

		mvc.perform(as(get(TAXPAYERS + "/" + id), token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ruc").value(ruc));
		mvc.perform(as(get(TAXPAYERS), token)).andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(id)));
		assertThat(jdbc.queryForObject("SELECT tenant_id FROM taxpayers WHERE id = ?", UUID.class, UUID.fromString(id)))
			.isEqualTo(tenant);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'TAXPAYER_CREATED' AND entity_id = ? AND tenant_id = ?",
				Integer.class, UUID.fromString(id), tenant))
			.isEqualTo(1);
	}

	@Test
	void billerAndAuditorCanReadButNotCreate() throws Exception {
		String owner = newUserToken();
		UUID tenant = createTenant(owner);
		createTaxpayer(owner, TestData.ruc());

		for (String role : new String[] { "BILLER", "AUDITOR" }) {
			String member = memberToken(tenant, role);
			mvc.perform(json(post(TAXPAYERS), member, taxpayerJson(TestData.ruc())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
			mvc.perform(as(get(TAXPAYERS), member))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
		}
		mvc.perform(json(post(TAXPAYERS), memberToken(tenant, "ADMIN"), taxpayerJson(TestData.ruc())))
			.andExpect(status().isCreated());
	}

	@Test
	void rejectsInvalidPayload() throws Exception {
		String token = newUserToken();
		createTenant(token);
		String valid = taxpayerJson(TestData.ruc());
		String ruc = JsonPath.read(valid, "$.ruc");

		expectInvalid(token, valid.replace(ruc, "179001234500"), "ruc");
		expectInvalid(token, valid.replace(ruc, "17900123450AB"), "ruc");
		expectInvalid(token, valid.replace(ruc, " " + ruc.substring(1)), "ruc");
		expectInvalid(token, valid.replace("\"defaultEnvironment\":\"1\"", "\"defaultEnvironment\":\"3\""),
				"defaultEnvironment");
		expectInvalid(token, valid.replace("Comercial Andina S.A.", ""), "legalName");
		expectInvalid(token, valid.replace("Comercial Andina S.A.", "Comercial\\u0000Andina"), "legalName");
		expectInvalid(token, valid.replace("\"tradeName\":\"Andina\"", "\"tradeName\":\"An\\u0007dina\""), "tradeName");
		expectInvalid(token, valid.replace("Av. Amazonas N34-12, Quito", "   "), "headOfficeAddress");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM taxpayers WHERE ruc = ?", Integer.class, ruc)).isZero();
	}

	@Test
	void rucMustEndInTheMainEstablishmentSuffix() throws Exception {
		String token = newUserToken();
		createTenant(token);
		// El XSD del SRI solo admite como RUC del emisor diez dígitos seguidos de 001.
		for (String ruc : new String[] { "1790012345002", "1790012345000", "1790012345010" }) {
			expectInvalid(token, taxpayerJson(ruc), "ruc");
		}
		mvc.perform(json(post(TAXPAYERS), token, taxpayerJson(TestData.ruc()))).andExpect(status().isCreated());
	}

	@Test
	void sameRucInSameTenantIsConflict() throws Exception {
		String token = newUserToken();
		createTenant(token);
		String ruc = TestData.ruc();
		createTaxpayer(token, ruc);

		mvc.perform(json(post(TAXPAYERS), token, taxpayerJson(ruc)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("RUC_ALREADY_REGISTERED"));
	}

	@Test
	void sameRucInAnotherTenantIsConflictWithoutLeak() throws Exception {
		String tokenA = newUserToken();
		UUID tenantA = createTenant(tokenA);
		String ruc = TestData.ruc();
		UUID taxpayerA = createTaxpayer(tokenA, ruc);
		String tokenB = newUserToken();
		createTenant(tokenB);

		String body = mvc.perform(json(post(TAXPAYERS), tokenB, taxpayerJson(ruc)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("RUC_ALREADY_REGISTERED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain(tenantA.toString(), taxpayerA.toString(), "Empresa de prueba");
	}

	@Test
	void concurrentRegistrationOfSameRucYieldsOneWinner() throws Exception {
		String ruc = TestData.ruc();
		String tokenA = newUserToken();
		createTenant(tokenA);
		String tokenB = newUserToken();
		createTenant(tokenB);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> a = () -> register(start, tokenA, ruc);
			Callable<Integer> b = () -> register(start, tokenB, ruc);
			Future<Integer> first = pool.submit(a);
			Future<Integer> second = pool.submit(b);
			start.countDown();
			assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM taxpayers WHERE ruc = ?", Integer.class, ruc))
			.isEqualTo(1);
	}

	@Test
	void rucKeepsLeadingZero() throws Exception {
		String token = newUserToken();
		createTenant(token);
		String ruc = "0" + TestData.ruc().substring(1);

		mvc.perform(json(post(TAXPAYERS), token, taxpayerJson(ruc)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.ruc").value(ruc));
	}

	private int register(CountDownLatch start, String token, String ruc) throws Exception {
		start.await();
		return mvc.perform(json(post(TAXPAYERS), token, taxpayerJson(ruc))).andReturn().getResponse().getStatus();
	}

	private void expectInvalid(String token, String payload, String field) throws Exception {
		mvc.perform(json(post(TAXPAYERS), token, payload))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[*].field", hasItem(field)));
	}

}
