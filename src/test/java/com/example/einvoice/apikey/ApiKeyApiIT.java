package com.example.einvoice.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

class ApiKeyApiIT extends AbstractIntegrationTest {

	static final String BASE = "/api/v1/api-keys";

	static final String SHAPE = "^efk_[A-Za-z0-9]{12}_[A-Za-z0-9_-]{43}$";

	String owner;

	UUID tenant;

	@BeforeEach
	void aCompany() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
	}

	static String body(String name) {
		return "{\"name\":\"" + name + "\"}";
	}

	ResultActions create(String token, String name) throws Exception {
		return mvc.perform(json(post(BASE), token, body(name)).header(TENANT_HEADER, tenant));
	}

	ResultActions list(String token) throws Exception {
		return mvc.perform(as(get(BASE), token).header(TENANT_HEADER, tenant));
	}

	ResultActions revoke(String token, Object id) throws Exception {
		return mvc.perform(as(delete(BASE + "/" + id), token).header(TENANT_HEADER, tenant));
	}

	String created(String name) throws Exception {
		return create(owner, name).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	int active() {
		return jdbc.queryForObject("SELECT count(*) FROM api_keys WHERE tenant_id = ? AND revoked_at IS NULL",
				Integer.class, tenant);
	}

	@Test
	void ownerCreatesListsAndRevokes() throws Exception {
		String response = created("ERP Contable");
		String id = JsonPath.read(response, "$.id");
		String key = JsonPath.read(response, "$.key");
		String publicId = JsonPath.read(response, "$.publicId");
		assertThat(key).matches(SHAPE);
		assertThat(key.substring(4, 16)).isEqualTo(publicId);
		String secret = key.substring(17);

		String email = jdbc.queryForObject(
				"SELECT u.email FROM users u JOIN memberships m ON m.user_id = u.id WHERE m.tenant_id = ? AND m.role = 'OWNER'",
				String.class, tenant);
		list(owner).andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].name").value("ERP Contable"))
			.andExpect(jsonPath("$[0].publicId").value(publicId))
			.andExpect(jsonPath("$[0].createdBy").value(email))
			.andExpect(jsonPath("$[0].lastUsedAt").doesNotExist())
			.andExpect(jsonPath("$[0].revokedAt").doesNotExist())
			.andExpect(jsonPath("$[0].key").doesNotExist())
			.andExpect(content().string(not(containsString(secret))));

		revoke(owner, id).andExpect(status().isNoContent());
		list(owner).andExpect(jsonPath("$[0].revokedAt").isNotEmpty());
		revoke(owner, id).andExpect(status().isNoContent());
	}

	@Test
	void adminCanManageKeys() throws Exception {
		create(memberToken(tenant, "ADMIN"), "Desde admin").andExpect(status().isCreated());
	}

	@Test
	void billerAndAuditorCannotManageKeys() throws Exception {
		String id = JsonPath.read(created("ERP Contable"), "$.id");
		for (String role : List.of("BILLER", "AUDITOR")) {
			String token = memberToken(tenant, role);
			create(token, "Otra").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
			list(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
			revoke(token, id).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
		}
	}

	@Test
	void tenthKeyIsTheLast() throws Exception {
		List<String> ids = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			ids.add(JsonPath.read(created("Key " + i), "$.id"));
		}
		create(owner, "Key 11").andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("API_KEY_LIMIT"));
		revoke(owner, ids.get(0)).andExpect(status().isNoContent());
		create(owner, "Key 11").andExpect(status().isCreated());
	}

	@Test
	void concurrentCreatesRespectTheLimit() throws Exception {
		for (int i = 1; i <= 9; i++) {
			created("Key " + i);
		}
		int threads = 5;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<MockHttpServletResponse>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			String name = "Concurrente " + i;
			results.add(pool.submit(() -> {
				start.await();
				return create(owner, name).andReturn().getResponse();
			}));
		}
		start.countDown();
		int created = 0;
		int limited = 0;
		for (Future<MockHttpServletResponse> result : results) {
			MockHttpServletResponse response = result.get();
			if (response.getStatus() == 201) {
				created++;
			}
			else if (response.getStatus() == 409 && response.getContentAsString().contains("API_KEY_LIMIT")) {
				limited++;
			}
		}
		pool.shutdown();
		assertThat(created).isEqualTo(1);
		assertThat(limited).isEqualTo(threads - 1);
		assertThat(active()).isEqualTo(10);
	}

	@Test
	void activeNamesAreUnique() throws Exception {
		String id = JsonPath.read(created("ERP Contable"), "$.id");
		create(owner, "ERP Contable").andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("API_KEY_NAME_TAKEN"));
		create(owner, "erp contable").andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("API_KEY_NAME_TAKEN"));
		revoke(owner, id).andExpect(status().isNoContent());
		create(owner, "ERP Contable").andExpect(status().isCreated());
	}

	@Test
	void nameIsValidated() throws Exception {
		// "\\u0007" llega al servidor como el escape JSON de un carácter de control.
		for (String bad : List.of("", "   ", "a".repeat(81), "ERP\\u0007")) {
			create(owner, bad).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}
		create(owner, "  ERP  ").andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("ERP"));
		list(owner).andExpect(jsonPath("$[0].name").value("ERP"));
	}

	@Test
	void otherTenantsKeysAreNotFound() throws Exception {
		String id = JsonPath.read(created("ERP Contable"), "$.id");
		String stranger = newUserToken();
		UUID other = createTenant(stranger);
		mvc.perform(as(delete(BASE + "/" + id), stranger).header(TENANT_HEADER, other))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("API_KEY_NOT_FOUND"));
		mvc.perform(as(get(BASE), stranger).header(TENANT_HEADER, other)).andExpect(jsonPath("$", hasSize(0)));
		assertThat(active()).isEqualTo(1);
	}

	@Test
	void creationAndRevocationAreAudited() throws Exception {
		String response = created("ERP Contable");
		String id = JsonPath.read(response, "$.id");
		String secret = JsonPath.<String>read(response, "$.key").substring(17);
		revoke(owner, id).andExpect(status().isNoContent());
		revoke(owner, id).andExpect(status().isNoContent());

		UUID ownerId = jdbc.queryForObject("SELECT user_id FROM memberships WHERE tenant_id = ? AND role = 'OWNER'",
				UUID.class, tenant);
		for (String action : List.of("API_KEY_CREATED", "API_KEY_REVOKED")) {
			var rows = jdbc.queryForList(
					"SELECT entity_type, entity_id, actor_id, metadata::text AS metadata FROM audit_events WHERE tenant_id = ? AND action = ?",
					tenant, action);
			// El segundo DELETE no cambia nada, así que no vuelve a auditar.
			assertThat(rows).hasSize(1);
			String metadata = rows.get(0).get("metadata").toString();
			assertThat(rows.get(0).get("entity_type")).isEqualTo("API_KEY");
			assertThat(rows.get(0).get("entity_id").toString()).isEqualTo(id);
			assertThat(rows.get(0).get("actor_id")).isEqualTo(ownerId);
			assertThat(JsonPath.<String>read(metadata, "$.name")).isEqualTo("ERP Contable");
			assertThat(metadata).doesNotContain(secret);
		}
	}

	@Test
	void listShowsActiveFirstThenNewest() throws Exception {
		String old = JsonPath.read(created("Antigua"), "$.id");
		revoke(owner, old).andExpect(status().isNoContent());
		created("Primera activa");
		created("Segunda activa");
		jdbc.update("UPDATE api_keys SET created_at = now() - interval '3 hours' WHERE name = 'Antigua'");
		jdbc.update("UPDATE api_keys SET created_at = now() - interval '2 hours' WHERE name = 'Primera activa'");
		jdbc.update("UPDATE api_keys SET created_at = now() - interval '1 hour' WHERE name = 'Segunda activa'");
		list(owner).andExpect(jsonPath("$", hasSize(3)))
			.andExpect(jsonPath("$[0].name").value("Segunda activa"))
			.andExpect(jsonPath("$[1].name").value("Primera activa"))
			.andExpect(jsonPath("$[2].name").value("Antigua"));
	}

}
