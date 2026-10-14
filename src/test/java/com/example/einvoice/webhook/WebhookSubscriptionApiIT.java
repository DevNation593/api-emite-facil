package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.einvoice.certificate.SecretCipher;
import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.Role;
import com.example.einvoice.tenancy.TenantAccess;

class WebhookSubscriptionApiIT extends AbstractIntegrationTest {

	static final String BASE = "/api/v1/webhooks/subscriptions";

	/** Con una ficha en la consulta, como es habitual: no debe salir de la tabla de suscripciones. */
	static final String URL = "https://93.184.216.34/facturas?ficha=ficha-del-receptor";

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	AuditLog audit;

	@Autowired
	SecretCipher cipher;

	@Autowired
	WebhookProperties properties;

	@Autowired
	PlatformTransactionManager transactionManager;

	String owner;

	UUID tenant;

	@BeforeEach
	void aCompany() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
	}

	static String body(String url, String... eventTypes) {
		return "{\"url\":\"" + url + "\",\"eventTypes\":[\"" + String.join("\",\"", eventTypes) + "\"]}";
	}

	ResultActions create(String token, String json) throws Exception {
		return mvc.perform(json(post(BASE), token, json).header(TENANT_HEADER, tenant));
	}

	/** Crea una suscripción y devuelve la respuesta, que es la única que trae el secreto. */
	String created(String... eventTypes) throws Exception {
		return create(owner, body(URL, eventTypes)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	ResultActions list(String token) throws Exception {
		return mvc.perform(as(get(BASE), token).header(TENANT_HEADER, tenant));
	}

	ResultActions update(String token, UUID tenantId, String id, String json) throws Exception {
		return mvc.perform(json(put(BASE + "/" + id), token, json).header(TENANT_HEADER, tenantId));
	}

	int events(String action) {
		return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id = ? AND action = ?", Integer.class,
				tenant, action);
	}

	@Test
	void theSecretIsShownOnceAndStoredEncrypted() throws Exception {
		String response = created("document.authorized", "document.rejected");
		String id = JsonPath.read(response, "$.id");
		String secret = JsonPath.read(response, "$.secret");

		assertThat(secret).startsWith("whsec_");
		assertThat(JsonPath.<String>read(response, "$.status")).isEqualTo("ACTIVE");
		list(owner).andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].url").value(URL))
			.andExpect(jsonPath("$[0].eventTypes", hasSize(2)))
			.andExpect(jsonPath("$[0].consecutiveFailures").value(0))
			.andExpect(jsonPath("$[0].secret").doesNotExist());
		// En la base no está ni el secreto ni su clave en claro.
		byte[] stored = jdbc.queryForObject("SELECT secret FROM webhook_subscriptions WHERE id = ?", byte[].class,
				UUID.fromString(id));
		assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(secret)
			.doesNotContain(secret.substring(6))
			.doesNotContain(new String(WebhookSigner.key(secret), StandardCharsets.ISO_8859_1));
	}

	@Test
	void onlyOwnersAndAdministratorsManageSubscriptions() throws Exception {
		String id = JsonPath.read(created("document.authorized"), "$.id");

		create(memberToken(tenant, "ADMIN"), body(URL, "document.received")).andExpect(status().isCreated());
		for (String role : List.of("BILLER", "AUDITOR")) {
			String token = memberToken(tenant, role);
			create(token, body(URL, "document.received")).andExpect(status().isForbidden());
			list(token).andExpect(status().isForbidden());
			update(token, tenant, id, "{\"url\":\"" + URL + "\",\"eventTypes\":[\"document.received\"],\"enabled\":false}")
				.andExpect(status().isForbidden());
			mvc.perform(as(delete(BASE + "/" + id), token).header(TENANT_HEADER, tenant)).andExpect(status().isForbidden());
			mvc.perform(as(post(BASE + "/" + id + "/secret"), token).header(TENANT_HEADER, tenant))
				.andExpect(status().isForbidden());
		}
	}

	@Test
	void malformedSubscriptionsAreRejected() throws Exception {
		create(owner, body(URL, "document.cancelled")).andExpect(status().isBadRequest());
		create(owner, "{\"url\":\"" + URL + "\",\"eventTypes\":[]}").andExpect(status().isBadRequest());
		create(owner, "{\"eventTypes\":[\"document.received\"]}").andExpect(status().isBadRequest());
		create(owner, body("ftp://93.184.216.34/x", "document.received")).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("WEBHOOK_URL_NOT_ALLOWED"));
		create(owner, body("https://usuario:clave@93.184.216.34/x", "document.received"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("WEBHOOK_URL_NOT_ALLOWED"));
		list(owner).andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void repeatedEventTypesCountOnce() throws Exception {
		String response = created("document.received", "document.received");

		assertThat(JsonPath.<List<String>>read(response, "$.eventTypes")).containsExactly("document.received");
	}

	@Test
	void thereIsALimitToSubscriptions() throws Exception {
		for (int i = 0; i < 10; i++) {
			created("document.received");
		}

		create(owner, body(URL, "document.received")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("WEBHOOK_LIMIT_REACHED"));
	}

	@Test
	void aSubscriptionCanBeChangedDisabledAndEnabledAgain() throws Exception {
		String id = JsonPath.read(created("document.authorized"), "$.id");
		jdbc.update("UPDATE webhook_subscriptions SET consecutive_failures = 7 WHERE id = ?", UUID.fromString(id));

		update(owner, tenant, id,
				"{\"url\":\"https://93.184.216.34/otra\",\"eventTypes\":[\"document.delivered\"],\"description\":\"ERP\",\"enabled\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.url").value("https://93.184.216.34/otra"))
			.andExpect(jsonPath("$.eventTypes[0]").value("document.delivered"))
			.andExpect(jsonPath("$.description").value("ERP"))
			.andExpect(jsonPath("$.status").value("DISABLED"))
			.andExpect(jsonPath("$.disabledReason").value("MANUAL"));

		update(owner, tenant, id, "{\"url\":\"https://93.184.216.34/otra\",\"eventTypes\":[\"document.delivered\"],\"enabled\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.disabledReason").value(nullValue()))
			.andExpect(jsonPath("$.consecutiveFailures").value(0));
		update(owner, tenant, id, "{\"url\":\"ftp://93.184.216.34/x\",\"eventTypes\":[\"document.delivered\"],\"enabled\":true}")
			.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void anAddressThatStoppedWorkingCanStillBeSwitchedOff() throws Exception {
		String id = JsonPath.read(created("document.authorized"), "$.id");
		// El dominio del receptor dejó de existir después de registrarlo.
		String gone = "https://receptor.no-existe.invalid/avisos";
		jdbc.update("UPDATE webhook_subscriptions SET url = ? WHERE id = ?", gone, UUID.fromString(id));
		String body = "{\"url\":\"" + gone + "\",\"eventTypes\":[\"document.authorized\"],\"enabled\":%s}";

		update(owner, tenant, id, body.formatted("false")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DISABLED"));

		// Encenderla, o cambiarla a otra que tampoco existe, sí vuelve a comprobarla.
		update(owner, tenant, id, body.formatted("true")).andExpect(status().isUnprocessableEntity());
		update(owner, tenant, id, body.formatted("false").replace("/avisos", "/otra")).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void theAddressIsLookedUpOutsideAnyTransaction() throws Exception {
		String id = JsonPath.read(created("document.authorized"), "$.id");
		List<Boolean> inTransaction = new java.util.concurrent.CopyOnWriteArrayList<>();
		// Resolver un nombre puede tardar lo que el receptor quiera: no puede retener una conexión ni un bloqueo.
		WebhookUrlPolicy watching = new WebhookUrlPolicy(true, true, host -> {
			inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
			return InetAddress.getAllByName(host);
		});
		WebhookSubscriptionService service = new WebhookSubscriptionService(jdbcClient, audit, cipher, watching, properties,
				transactionManager);
		TenantAccess access = new TenantAccess(
				jdbc.queryForObject("SELECT user_id FROM memberships WHERE tenant_id = ?", UUID.class, tenant), tenant, Role.OWNER);

		service.create(access, "https://93.184.216.34/a", List.of("document.received"), null);
		service.update(access, UUID.fromString(id), "https://93.184.216.34/b", List.of("document.received"), null, true);

		assertThat(inTransaction).hasSize(2).containsOnly(false);
		assertThat(events("WEBHOOK_CREATED")).isEqualTo(2);
		assertThat(events("WEBHOOK_UPDATED")).isEqualTo(1);
	}

	@Test
	void rotatingGivesANewSecretAndKeepsTheOldOneForADay() throws Exception {
		String response = created("document.authorized");
		String id = JsonPath.read(response, "$.id");
		String first = JsonPath.read(response, "$.secret");

		String rotated = mvc.perform(as(post(BASE + "/" + id + "/secret"), owner).header(TENANT_HEADER, tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.previousSecretExpiresAt").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(JsonPath.<String>read(rotated, "$.secret")).startsWith("whsec_").isNotEqualTo(first);
		assertThat(jdbc.queryForObject("""
				SELECT previous_secret IS NOT NULL
				   AND previous_secret_expires_at BETWEEN now() + interval '23 hours' AND now() + interval '25 hours'
				  FROM webhook_subscriptions WHERE id = ?
				""", Boolean.class, UUID.fromString(id))).isTrue();
		list(owner).andExpect(jsonPath("$[0].previousSecretExpiresAt").isNotEmpty())
			.andExpect(jsonPath("$[0].secret").doesNotExist());
	}

	@Test
	void aDeletedSubscriptionIsGone() throws Exception {
		String id = JsonPath.read(created("document.authorized"), "$.id");

		mvc.perform(as(delete(BASE + "/" + id), owner).header(TENANT_HEADER, tenant)).andExpect(status().isNoContent());

		list(owner).andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(as(delete(BASE + "/" + id), owner).header(TENANT_HEADER, tenant)).andExpect(status().isNotFound());
	}

	@Test
	void anotherCompanySeesAndChangesNothing() throws Exception {
		String id = JsonPath.read(created("document.authorized"), "$.id");
		String stranger = newUserToken();
		UUID other = createTenant(stranger);

		mvc.perform(as(get(BASE), stranger).header(TENANT_HEADER, other)).andExpect(jsonPath("$", hasSize(0)));
		update(stranger, other, id, "{\"url\":\"" + URL + "\",\"eventTypes\":[\"document.received\"],\"enabled\":false}")
			.andExpect(status().isNotFound());
		mvc.perform(as(post(BASE + "/" + id + "/secret"), stranger).header(TENANT_HEADER, other))
			.andExpect(status().isNotFound());
		mvc.perform(as(delete(BASE + "/" + id), stranger).header(TENANT_HEADER, other)).andExpect(status().isNotFound());
		// Y con la cabecera de la empresa ajena, ni eso.
		mvc.perform(as(get(BASE), stranger).header(TENANT_HEADER, tenant)).andExpect(status().isForbidden());
		list(owner).andExpect(jsonPath("$[0].status").value("ACTIVE"));
	}

	@Test
	void everyChangeIsAuditedWithoutTheAddressOrTheSecret() throws Exception {
		String response = created("document.authorized");
		String id = JsonPath.read(response, "$.id");
		String secret = JsonPath.read(response, "$.secret");
		update(owner, tenant, id, "{\"url\":\"" + URL + "\",\"eventTypes\":[\"document.received\"],\"enabled\":false}")
			.andExpect(status().isOk());
		String rotated = JsonPath.read(mvc.perform(as(post(BASE + "/" + id + "/secret"), owner).header(TENANT_HEADER, tenant))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.secret");
		mvc.perform(as(delete(BASE + "/" + id), owner).header(TENANT_HEADER, tenant)).andExpect(status().isNoContent());

		for (String action : List.of("WEBHOOK_CREATED", "WEBHOOK_UPDATED", "WEBHOOK_SECRET_ROTATED", "WEBHOOK_DELETED")) {
			assertThat(events(action)).as(action).isEqualTo(1);
		}
		String audited = String.join(" ", jdbc.queryForList(
				"SELECT metadata::text FROM audit_events WHERE tenant_id = ? AND action LIKE 'WEBHOOK_%'", String.class, tenant));
		assertThat(audited).doesNotContain("ficha-del-receptor", "93.184.216.34", secret, secret.substring(6), rotated);
	}

}
