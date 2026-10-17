package com.example.einvoice.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.jayway.jsonpath.JsonPath;

/** Un sistema externo llama a la API con X-Api-Key, como un facturador a nombre de quien creó la key. */
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyAuthenticationIT extends AbstractIntegrationTest {

	static final String KEY_HEADER = "X-Api-Key";

	static final String UNAUTHENTICATED_DETAIL = "Se requiere un token de acceso válido";

	BillingScenario scenario;

	String key;

	UUID keyId;

	@BeforeEach
	void aCompanyWithAKey() throws Exception {
		scenario = newBillingScenario();
		String response = mvc
			.perform(json(post("/api/v1/api-keys"), scenario.owner(), "{\"name\":\"ERP Contable\"}")
				.header(TENANT_HEADER, scenario.tenant()))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		key = JsonPath.read(response, "$.key");
		keyId = UUID.fromString(JsonPath.read(response, "$.id"));
	}

	/** Solo la key: sin Authorization. */
	static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, String key) {
		return request.header(KEY_HEADER, key);
	}

	static MockHttpServletRequestBuilder jsonWithKey(MockHttpServletRequestBuilder request, String key, String body) {
		return withKey(request, key).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	ResultActions currentTenant(String key) throws Exception {
		return mvc.perform(withKey(get("/api/v1/tenants/current"), key));
	}

	ResultActions expectUnauthenticated(ResultActions result) throws Exception {
		return result.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.detail").value(UNAUTHENTICATED_DETAIL));
	}

	String secret() {
		return key.substring(ApiKeyCredential.PREFIX.length() + 13);
	}

	OffsetDateTime lastUsedAt() {
		return jdbc.queryForObject("SELECT last_used_at FROM api_keys WHERE id = ?", OffsetDateTime.class, keyId);
	}

	@Test
	void aKeyIssuesAndReadsAnInvoice() throws Exception {
		String issued = mvc
			.perform(jsonWithKey(post("/api/v1/invoices"), key, scenario.invoiceJson("1", "11.50"))
				.header("Idempotency-Key", UUID.randomUUID().toString()))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonPath.read(issued, "$.id");

		mvc.perform(withKey(get("/api/v1/documents/" + id), key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id));
		currentTenant(key).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(scenario.tenant().toString()))
			.andExpect(jsonPath("$.role").value("BILLER"));
	}

	@Test
	void invalidKeysAllAnswerTheSame() throws Exception {
		String revokedKey = JsonPath.read(mvc
			.perform(json(post("/api/v1/api-keys"), scenario.owner(), "{\"name\":\"Vieja\"}")
				.header(TENANT_HEADER, scenario.tenant()))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.key");
		jdbc.update("UPDATE api_keys SET revoked_at = now(), revoked_by = created_by WHERE name = 'Vieja'");

		String publicId = key.substring(4, 16);
		String otherSecret = "A".repeat(43);
		List<String> bad = List.of("efk_corta", "efk_ZZZZZZZZZZZZ_" + secret(), "efk_" + publicId + "_" + otherSecret,
				revokedKey);
		List<String> bodies = new ArrayList<>();
		for (String value : bad) {
			MockHttpServletResponse response = expectUnauthenticated(currentTenant(value)).andReturn().getResponse();
			String body = response.getContentAsString();
			bodies.add(JsonPath.read(body, "$.status") + "|" + JsonPath.read(body, "$.title") + "|"
					+ JsonPath.read(body, "$.errorCode") + "|" + JsonPath.read(body, "$.detail"));
		}
		assertThat(bodies).containsOnly(bodies.get(0));
	}

	@Test
	void emptyHeaderIsRejected() throws Exception {
		expectUnauthenticated(currentTenant(""));
		expectUnauthenticated(currentTenant("   "));
	}

	@Test
	void surroundingWhitespaceIsIgnored() throws Exception {
		// Un salto de línea no llega nunca dentro de una cabecera HTTP; los espacios copiados sí.
		currentTenant("  " + key + "  ").andExpect(status().isOk());
	}

	@Test
	void revokedKeyStopsAtOnce() throws Exception {
		currentTenant(key).andExpect(status().isOk());
		mvc.perform(as(delete("/api/v1/api-keys/" + keyId),
				scenario.owner()).header(TENANT_HEADER, scenario.tenant()))
			.andExpect(status().isNoContent());
		expectUnauthenticated(currentTenant(key));
	}

	@Test
	void bothCredentialsAreAmbiguous() throws Exception {
		mvc.perform(withKey(get("/api/v1/tenants/current"), key).header(HttpHeaders.AUTHORIZATION, scenario.owner()))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errorCode").value("CREDENTIALS_AMBIGUOUS"));
		// Aunque la key venga vacía: la petición trae dos credenciales y no se elige una.
		mvc.perform(withKey(get("/api/v1/tenants/current"), "").header(HttpHeaders.AUTHORIZATION, scenario.owner()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("CREDENTIALS_AMBIGUOUS"));
	}

	@Test
	void otherTenantHeaderIsForbidden() throws Exception {
		UUID other = createTenant(newUserToken());
		mvc.perform(withKey(get("/api/v1/tenants/current"), key).header(TENANT_HEADER, other))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("TENANT_FORBIDDEN"));
		mvc.perform(withKey(get("/api/v1/tenants/current"), key).header(TENANT_HEADER, scenario.tenant()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(scenario.tenant().toString()));
		mvc.perform(withKey(get("/api/v1/tenants/current"), key).header(TENANT_HEADER, "no-es-un-uuid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("TENANT_HEADER_INVALID"));
	}

	@Test
	void configurationIsOutOfReach() throws Exception {
		List<MockHttpServletRequestBuilder> requests = List.of(
				jsonWithKey(post("/api/v1/taxpayers/" + scenario.taxpayer() + "/establishments"), key,
						"{\"code\":\"002\",\"address\":\"Av. 10 de Agosto, Quito\"}"),
				jsonWithKey(post("/api/v1/certificates"), key,
						"{\"taxpayerId\":\"" + scenario.taxpayer() + "\",\"p12\":\"AAAA\",\"password\":\"clave\"}"),
				jsonWithKey(post("/api/v1/webhooks/subscriptions"), key,
						"{\"url\":\"https://erp.example.test/avisos\",\"eventTypes\":[\"document.authorized\"]}"),
				jsonWithKey(post("/api/v1/api-keys"), key, "{\"name\":\"Otra\"}"), withKey(get("/api/v1/api-keys"), key));
		for (MockHttpServletRequestBuilder request : requests) {
			mvc.perform(request)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM api_keys WHERE tenant_id = ?", Integer.class,
				scenario.tenant()))
			.isEqualTo(1);
	}

	@Test
	void personOnlyEndpointsRejectKeys() throws Exception {
		List<MockHttpServletRequestBuilder> requests = List.of(
				jsonWithKey(post("/api/v1/tenants"), key, "{\"name\":\"Otra empresa\"}"),
				withKey(get("/api/v1/tenants"), key), withKey(get("/api/v1/auth/me"), key),
				jsonWithKey(post("/api/v1/auth/password"), key,
						"{\"currentPassword\":\"una-clave-cualquiera\",\"newPassword\":\"otra-clave-bien-larga\"}"),
				withKey(post("/api/v1/auth/email-verification/resend"), key));
		for (MockHttpServletRequestBuilder request : requests) {
			mvc.perform(request)
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errorCode").value("API_KEY_NOT_ALLOWED"));
		}
	}

	@Test
	void suspendedTenantDisablesTheKey() throws Exception {
		jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", scenario.tenant());
		expectUnauthenticated(currentTenant(key));
	}

	@Test
	void creatorWithoutAccessDisablesTheKey() throws Exception {
		jdbc.update(
				"UPDATE memberships SET status = 'SUSPENDED' WHERE tenant_id = ? AND user_id = (SELECT created_by FROM api_keys WHERE id = ?)",
				scenario.tenant(), keyId);
		expectUnauthenticated(currentTenant(key));
	}

	@Test
	void creatorWhoCannotBillDisablesTheKey() throws Exception {
		// Sigue en la empresa, pero como AUDITOR: ya no factura, así que su key tampoco.
		jdbc.update(
				"UPDATE memberships SET role = 'AUDITOR' WHERE tenant_id = ? AND user_id = (SELECT created_by FROM api_keys WHERE id = ?)",
				scenario.tenant(), keyId);
		expectUnauthenticated(currentTenant(key));
	}

	@Test
	void aKeyCreatesProductsButABillerPersonDoesNot() throws Exception {
		String created = mvc.perform(jsonWithKey(post("/api/v1/products"), key, productJson("TIENDA-1", "4.50", "4")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sku").value("TIENDA-1"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID productId = UUID.fromString(JsonPath.read(created, "$.id"));
		assertThat(jdbc.queryForList(
				"SELECT actor_id, api_key_id FROM audit_events WHERE tenant_id = ? AND action = 'PRODUCT_CREATED' AND entity_id = ?",
				scenario.tenant(), productId))
			.singleElement()
			.satisfies(row -> assertThat(row.get("api_key_id")).isEqualTo(keyId));

		String biller = memberToken(scenario.tenant(), "BILLER");
		mvc.perform(json(post("/api/v1/products"), biller, productJson("TIENDA-2", "4.50", "4")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
	}

	@Test
	void lastUsedAtIsWrittenAtMostOncePerMinute() throws Exception {
		assertThat(lastUsedAt()).isNull();
		currentTenant(key).andExpect(status().isOk());
		OffsetDateTime first = lastUsedAt();
		assertThat(first).isNotNull();
		currentTenant(key).andExpect(status().isOk());
		assertThat(lastUsedAt()).isEqualTo(first);

		jdbc.update("UPDATE api_keys SET last_used_at = now() - interval '2 minutes' WHERE id = ?", keyId);
		OffsetDateTime old = lastUsedAt();
		currentTenant(key).andExpect(status().isOk());
		assertThat(lastUsedAt()).isAfter(old);
	}

	@Test
	void keyValueNeverReachesTheLogs(CapturedOutput output) throws Exception {
		currentTenant(key).andExpect(status().isOk());
		String publicId = key.substring(4, 16);
		expectUnauthenticated(currentTenant("efk_" + publicId + "_" + "B".repeat(43)));
		mvc.perform(withKey(get("/api/v1/tenants/current"), key).header(HttpHeaders.AUTHORIZATION, scenario.owner()))
			.andExpect(status().isBadRequest());
		assertThat(output.getAll()).doesNotContain(secret()).doesNotContain(key);
	}

	@Test
	void auditNamesTheKeyAndItsCreator() throws Exception {
		UUID creator = jdbc.queryForObject("SELECT created_by FROM api_keys WHERE id = ?", UUID.class, keyId);
		String issued = mvc
			.perform(jsonWithKey(post("/api/v1/invoices"), key, scenario.invoiceJson("1", "11.50"))
				.header("Idempotency-Key", UUID.randomUUID().toString()))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID documentId = UUID.fromString(JsonPath.read(issued, "$.id"));
		mvc.perform(jsonWithKey(post("/api/v1/customers"), key, customerJson("06", "PA1234567", "María Pérez")))
			.andExpect(status().isCreated());

		for (String action : List.of("INVOICE_RESERVED", "CUSTOMER_CREATED")) {
			assertThat(jdbc.queryForList(
					"SELECT actor_id, api_key_id FROM audit_events WHERE tenant_id = ? AND action = ? AND api_key_id = ?",
					scenario.tenant(), action, keyId))
				.singleElement()
				.satisfies(row -> {
					assertThat(row.get("actor_id")).isEqualTo(creator);
					assertThat(row.get("api_key_id")).isEqualTo(keyId);
				});
		}
		assertThat(jdbc.queryForObject("SELECT created_by FROM fiscal_documents WHERE id = ?", UUID.class,
				documentId))
			.isEqualTo(creator);

		// El mismo dueño con su sesión: el evento no lleva key.
		String own = mvc
			.perform(json(post("/api/v1/customers"), scenario.owner(), customerJson("06", "PA7654321", "Ana Ruiz"))
				.header(TENANT_HEADER, scenario.tenant()))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(jdbc.queryForList("SELECT api_key_id FROM audit_events WHERE action = 'CUSTOMER_CREATED' AND entity_id = ?",
				UUID.fromString(JsonPath.read(own, "$.id"))))
			.singleElement()
			.satisfies(row -> assertThat(row.get("api_key_id")).isNull());
	}

}
