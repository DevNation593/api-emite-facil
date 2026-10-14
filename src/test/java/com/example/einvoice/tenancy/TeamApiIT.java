package com.example.einvoice.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestTokens;
import com.jayway.jsonpath.JsonPath;

/** Quién forma parte de la empresa, con qué rol, y quién puede cambiarlo. */
@TestPropertySource(properties = "einvoice.invitations.allow-without-email=true")
class TeamApiIT extends AbstractIntegrationTest {

	String owner;

	UUID tenant;

	@BeforeEach
	void aCompany() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
	}

	/** Miembro nuevo con ese rol, con su token y su identificador de usuario. */
	record Member(String token, UUID userId) {
	}

	Member member(String role) throws Exception {
		String subject = "u-" + UUID.randomUUID();
		UUID userId = jdbc.queryForObject("INSERT INTO users (issuer, subject, email) VALUES (?, ?, ?) RETURNING id",
				UUID.class, TestTokens.ISSUER, subject, subject + "@example.test");
		jdbc.update("INSERT INTO memberships (user_id, tenant_id, role) VALUES (?, ?, ?)", userId, tenant, role);
		return new Member(TestTokens.bearer(subject), userId);
	}

	UUID ownerId() {
		return jdbc.queryForObject("SELECT user_id FROM memberships WHERE tenant_id = ? AND role = 'OWNER'", UUID.class,
				tenant);
	}

	ResultActions changeRole(String token, UUID userId, String role) throws Exception {
		return mvc.perform(json(put("/api/v1/members/" + userId + "/role"), token, "{\"role\":\"" + role + "\"}")
			.header(TENANT_HEADER, tenant));
	}

	ResultActions remove(String token, UUID userId) throws Exception {
		return mvc.perform(as(delete("/api/v1/members/" + userId), token).header(TENANT_HEADER, tenant));
	}

	String role(UUID userId) {
		return jdbc.queryForObject("SELECT role FROM memberships WHERE tenant_id = ? AND user_id = ?", String.class,
				tenant, userId);
	}

	int events(String action) {
		return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id = ? AND action = ?", Integer.class,
				tenant, action);
	}

	@Test
	void ownersAndAdministratorsSeeTheTeam() throws Exception {
		Member biller = member("BILLER");
		Member admin = member("ADMIN");

		mvc.perform(as(get("/api/v1/members"), owner).header(TENANT_HEADER, tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(3)))
			.andExpect(jsonPath("$[0].role").value("OWNER"))
			.andExpect(jsonPath("$[0].self").value(true))
			.andExpect(jsonPath("$[?(@.userId == '" + biller.userId() + "')].role").value("BILLER"))
			.andExpect(jsonPath("$[?(@.userId == '" + biller.userId() + "')].self").value(false))
			.andExpect(jsonPath("$[?(@.userId == '" + biller.userId() + "')].email").isNotEmpty());
		mvc.perform(as(get("/api/v1/members"), admin.token()).header(TENANT_HEADER, tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(3)));
		// Los correos del equipo no son para todos.
		mvc.perform(as(get("/api/v1/members"), biller.token()).header(TENANT_HEADER, tenant))
			.andExpect(status().isForbidden());
		mvc.perform(as(get("/api/v1/members"), member("AUDITOR").token()).header(TENANT_HEADER, tenant))
			.andExpect(status().isForbidden());
	}

	@Test
	void onlyTheOwnerChangesRoles() throws Exception {
		Member biller = member("BILLER");
		Member admin = member("ADMIN");

		changeRole(owner, biller.userId(), "ADMIN").andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andExpect(jsonPath("$.userId").value(biller.userId().toString()));
		assertThat(role(biller.userId())).isEqualTo("ADMIN");
		assertThat(events("MEMBER_ROLE_CHANGED")).isEqualTo(1);
		// El cambio vale desde la siguiente petición.
		mvc.perform(as(get("/api/v1/tenants/current"), biller.token()).header(TENANT_HEADER, tenant))
			.andExpect(jsonPath("$.role").value("ADMIN"));

		changeRole(admin.token(), biller.userId(), "AUDITOR").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
		assertThat(role(biller.userId())).isEqualTo("ADMIN");
	}

	@Test
	void theOwnerRoleIsNeitherGrantedNorTakenAway() throws Exception {
		Member admin = member("ADMIN");

		changeRole(owner, admin.userId(), "OWNER").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_NOT_GRANTABLE"));
		changeRole(owner, ownerId(), "ADMIN").andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("OWNER_PROTECTED"));
		remove(owner, ownerId()).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OWNER_PROTECTED"));
		remove(admin.token(), ownerId()).andExpect(status().isConflict());

		assertThat(role(ownerId())).isEqualTo("OWNER");
		assertThat(role(admin.userId())).isEqualTo("ADMIN");
	}

	@Test
	void aRemovedMemberLosesAccessOnTheirNextRequest() throws Exception {
		Member biller = member("BILLER");
		mvc.perform(as(get("/api/v1/customers"), biller.token()).header(TENANT_HEADER, tenant)).andExpect(status().isOk());

		remove(owner, biller.userId()).andExpect(status().isNoContent());

		mvc.perform(as(get("/api/v1/customers"), biller.token()).header(TENANT_HEADER, tenant))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("TENANT_FORBIDDEN"));
		mvc.perform(as(get("/api/v1/tenants"), biller.token())).andExpect(jsonPath("$", hasSize(0)));
		assertThat(events("MEMBER_REMOVED")).isEqualTo(1);
		// Lo que hizo sigue registrado a su nombre: el usuario no se borra.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, biller.userId()))
			.isEqualTo(1);
		remove(owner, biller.userId()).andExpect(status().isNotFound());
	}

	@Test
	void removingAMemberCancelsTheInvitationsTheyLeftPending() throws Exception {
		Member admin = member("ADMIN");
		String ownEmail = "otra-cuenta-" + UUID.randomUUID() + "@example.test";
		String body = mvc
			.perform(json(post("/api/v1/invitations"), admin.token(), "{\"email\":\"" + ownEmail + "\",\"role\":\"BILLER\"}")
				.header(TENANT_HEADER, tenant))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String code = JsonPath.read(body, "$.token");
		mvc.perform(json(post("/api/v1/invitations"), owner, "{\"email\":\"nueva@example.test\",\"role\":\"AUDITOR\"}")
			.header(TENANT_HEADER, tenant)).andExpect(status().isCreated());

		remove(owner, admin.userId()).andExpect(status().isNoContent());

		// Si no, quien sale podría volver a entrar con otra cuenta que se invitó a sí mismo.
		mvc.perform(json(post("/api/v1/invitations/accept"), TestTokens.bearer("u-" + UUID.randomUUID(), ownEmail),
				"{\"token\":\"" + code + "\"}"))
			.andExpect(status().isNotFound());
		mvc.perform(as(get("/api/v1/invitations"), owner).header(TENANT_HEADER, tenant))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].email").value("nueva@example.test"));
		assertThat(events("INVITATION_REVOKED")).isEqualTo(1);
	}

	@Test
	void anAdministratorRemovesOnlyThoseBelow() throws Exception {
		Member admin = member("ADMIN");
		Member other = member("ADMIN");
		Member auditor = member("AUDITOR");

		remove(admin.token(), auditor.userId()).andExpect(status().isNoContent());
		remove(admin.token(), other.userId()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_NOT_GRANTABLE"));
		remove(admin.token(), admin.userId()).andExpect(status().isForbidden());
		remove(member("BILLER").token(), other.userId()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
	}

	@Test
	void membersOfAnotherCompanyAreOutOfReach() throws Exception {
		String stranger = newUserToken();
		UUID otherTenant = createTenant(stranger);
		UUID strangerId = jdbc.queryForObject("SELECT user_id FROM memberships WHERE tenant_id = ?", UUID.class, otherTenant);
		Member biller = member("BILLER");

		changeRole(owner, strangerId, "BILLER").andExpect(status().isNotFound());
		remove(owner, strangerId).andExpect(status().isNotFound());
		// Y desde fuera no se toca a los de esta.
		mvc.perform(as(delete("/api/v1/members/" + biller.userId()), stranger)).andExpect(status().isNotFound());
		assertThat(role(biller.userId())).isEqualTo("BILLER");
	}

	@Test
	void withoutAnEmailInTheTokenTheLinkAloneIsEnoughOnlyIfTheProviderIsConfiguredSo() throws Exception {
		String body = mvc
			.perform(json(post("/api/v1/invitations"), owner, "{\"email\":\"sin-correo@example.test\",\"role\":\"AUDITOR\"}")
				.header(TENANT_HEADER, tenant))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String code = JsonPath.read(body, "$.token");

		// Aquí einvoice.invitations.allow-without-email está activado.
		mvc.perform(json(post("/api/v1/invitations/accept"), TestTokens.bearerWithoutEmail("u-" + UUID.randomUUID()),
				"{\"token\":\"" + code + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("AUDITOR"));
	}

	@Test
	void evenThenAnotherEmailIsRefused() throws Exception {
		String body = mvc
			.perform(json(post("/api/v1/invitations"), owner, "{\"email\":\"esperada@example.test\",\"role\":\"AUDITOR\"}")
				.header(TENANT_HEADER, tenant))
			.andReturn()
			.getResponse()
			.getContentAsString();

		mvc.perform(json(post("/api/v1/invitations/accept"), TestTokens.bearer("u-" + UUID.randomUUID(), "otra@example.test"),
				"{\"token\":\"" + JsonPath.<String>read(body, "$.token") + "\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("INVITATION_EMAIL_MISMATCH"));
	}

}
