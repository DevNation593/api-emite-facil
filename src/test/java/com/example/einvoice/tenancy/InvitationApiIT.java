package com.example.einvoice.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestTokens;
import com.jayway.jsonpath.JsonPath;

/** Sumar a alguien a una empresa: un enlace de un solo uso, ligado a su correo y con caducidad. */
class InvitationApiIT extends AbstractIntegrationTest {

	String owner;

	UUID tenant;

	String guestEmail;

	String guest;

	@BeforeEach
	void aCompanyAndSomeoneToInvite() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
		guestEmail = "ana-" + UUID.randomUUID() + "@example.test";
		guest = TestTokens.bearer("u-" + UUID.randomUUID(), guestEmail);
	}

	static String invitation(String email, String role) {
		return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
	}

	ResultActions invite(String token, String email, String role) throws Exception {
		return mvc.perform(json(post("/api/v1/invitations"), token, invitation(email, role)).header(TENANT_HEADER, tenant));
	}

	/** Invita y devuelve el código, que solo se conoce en este momento. */
	String invited(String email, String role) throws Exception {
		String body = invite(owner, email, role).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.token");
	}

	ResultActions accept(String token, String code) throws Exception {
		return mvc.perform(json(post("/api/v1/invitations/accept"), token, "{\"token\":\"" + code + "\"}"));
	}

	ResultActions preview(String token, String code) throws Exception {
		return mvc.perform(json(post("/api/v1/invitations/preview"), token, "{\"token\":\"" + code + "\"}"));
	}

	int members() {
		return jdbc.queryForObject("SELECT count(*) FROM memberships WHERE tenant_id = ?", Integer.class, tenant);
	}

	int events(String action) {
		return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id = ? AND action = ?", Integer.class,
				tenant, action);
	}

	@Test
	void theCodeIsShownOnceAndNeverStored() throws Exception {
		String body = invite(owner, guestEmail, "BILLER").andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(guestEmail))
			.andExpect(jsonPath("$.role").value("BILLER"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String code = JsonPath.read(body, "$.token");

		assertThat(code).hasSizeGreaterThanOrEqualTo(40);
		// En la base solo queda su huella; ni la tabla, ni la auditoría, ni la lista lo contienen.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM invitations i WHERE i::text LIKE '%' || ? || '%'",
				Integer.class, code))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events a WHERE a::text LIKE '%' || ? || '%'",
				Integer.class, code))
			.isZero();
		String listed = mvc.perform(as(get("/api/v1/invitations"), owner).header(TENANT_HEADER, tenant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].email").value(guestEmail))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(listed).doesNotContain(code).doesNotContain("token");
		assertThat(events("INVITATION_CREATED")).isEqualTo(1);
	}

	@Test
	void theGuestSeesWhatTheyAreJoiningAndBecomesAMember() throws Exception {
		String code = invited(guestEmail, "BILLER");

		preview(guest, code).andExpect(status().isOk())
			.andExpect(jsonPath("$.tenantName").value("Empresa de prueba"))
			.andExpect(jsonPath("$.role").value("BILLER"));
		assertThat(members()).isEqualTo(1);

		accept(guest, code).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(tenant.toString()))
			.andExpect(jsonPath("$.role").value("BILLER"));

		assertThat(members()).isEqualTo(2);
		assertThat(events("INVITATION_ACCEPTED")).isEqualTo(1);
		mvc.perform(as(get("/api/v1/tenants/current"), guest)).andExpect(jsonPath("$.role").value("BILLER"));
		mvc.perform(as(get("/api/v1/invitations"), owner).header(TENANT_HEADER, tenant))
			.andExpect(jsonPath("$", hasSize(0)));
		// Un solo uso.
		accept(guest, code).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("INVITATION_NOT_VALID"));
		assertThat(members()).isEqualTo(2);
	}

	@Test
	void theEmailIsComparedWithoutRegardToCase() throws Exception {
		String code = invited(guestEmail.toUpperCase(), "AUDITOR");

		accept(guest, code).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("AUDITOR"));
	}

	@Test
	void twoAcceptancesAtOnceMakeOneMembership() throws Exception {
		String code = invited(guestEmail, "BILLER");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> attempt = () -> {
				start.await();
				return accept(guest, code).andReturn().getResponse().getStatus();
			};
			Future<Integer> first = pool.submit(attempt);
			Future<Integer> second = pool.submit(attempt);
			start.countDown();

			assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 404);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(members()).isEqualTo(2);
	}

	@Test
	void anExpiredARevokedOrAnInventedCodeAllLookTheSame() throws Exception {
		String expired = invited(guestEmail, "BILLER");
		jdbc.update("UPDATE invitations SET expires_at = now() - interval '1 minute' WHERE tenant_id = ?", tenant);
		String expiredAnswer = accept(guest, expired).andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString();
		preview(guest, expired).andExpect(status().isNotFound());

		String revoked = invited(guestEmail, "BILLER");
		UUID id = jdbc.queryForObject(
				"SELECT id FROM invitations WHERE tenant_id = ? AND revoked_at IS NULL AND accepted_at IS NULL", UUID.class,
				tenant);
		mvc.perform(as(delete("/api/v1/invitations/" + id), owner).header(TENANT_HEADER, tenant))
			.andExpect(status().isNoContent());
		String revokedAnswer = accept(guest, revoked).andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String inventedAnswer = accept(guest, "no-es-un-codigo-que-exista-en-ningun-sitio-0000")
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString();

		// Salvo el identificador de correlación, las tres respuestas son la misma.
		assertThat(List.of(expiredAnswer, revokedAnswer, inventedAnswer))
			.extracting(answer -> JsonPath.<String>read(answer, "$.errorCode") + JsonPath.<String>read(answer, "$.detail"))
			.containsOnly("INVITATION_NOT_VALID" + JsonPath.<String>read(inventedAnswer, "$.detail"));
		assertThat(members()).isEqualTo(1);
		// La caducada, al sustituirla la segunda invitación, y la que se revocó a mano.
		assertThat(events("INVITATION_REVOKED")).isEqualTo(2);
	}

	@Test
	void theLinkOnlyWorksForTheInvitedEmail() throws Exception {
		String code = invited(guestEmail, "BILLER");
		String someoneElse = TestTokens.bearer("u-" + UUID.randomUUID(), "otra@example.test");

		accept(someoneElse, code).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("INVITATION_EMAIL_MISMATCH"));
		preview(someoneElse, code).andExpect(status().isForbidden());
		accept(TestTokens.bearerWithoutEmail("u-" + UUID.randomUUID()), code).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("INVITATION_EMAIL_MISMATCH"));
		accept(TestTokens.bearer("u-" + UUID.randomUUID(), guestEmail, false), code).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("EMAIL_NOT_VERIFIED"));
		assertThat(members()).isEqualTo(1);

		// Ninguno de esos intentos la gastó.
		accept(guest, code).andExpect(status().isOk());
	}

	@Test
	void someoneWhoIsAlreadyAMemberIsNotInvitedAgain() throws Exception {
		accept(guest, invited(guestEmail, "BILLER")).andExpect(status().isOk());

		invite(owner, guestEmail, "AUDITOR").andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("ALREADY_MEMBER"));
	}

	@Test
	void nobodyGrantsARoleAboveTheirOwn() throws Exception {
		String admin = memberToken(tenant, "ADMIN");

		invite(admin, "b@example.test", "BILLER").andExpect(status().isCreated());
		invite(admin, "c@example.test", "AUDITOR").andExpect(status().isCreated());
		invite(admin, "d@example.test", "ADMIN").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_NOT_GRANTABLE"));
		invite(owner, "e@example.test", "OWNER").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_NOT_GRANTABLE"));
		invite(owner, "f@example.test", "ADMIN").andExpect(status().isCreated());
		for (String role : List.of("BILLER", "AUDITOR")) {
			invite(memberToken(tenant, role), "g@example.test", "AUDITOR").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
			mvc.perform(as(get("/api/v1/invitations"), memberToken(tenant, role)).header(TENANT_HEADER, tenant))
				.andExpect(status().isForbidden());
		}
	}

	@Test
	void anAdministratorCannotRevokeAnInvitationToBecomeAdministrator() throws Exception {
		invited("admin-nuevo@example.test", "ADMIN");
		UUID id = jdbc.queryForObject("SELECT id FROM invitations WHERE tenant_id = ?", UUID.class, tenant);

		mvc.perform(as(delete("/api/v1/invitations/" + id), memberToken(tenant, "ADMIN")).header(TENANT_HEADER, tenant))
			.andExpect(status().isForbidden());
	}

	@Test
	void invitingTheSameEmailAgainReplacesThePendingInvitation() throws Exception {
		String first = invited(guestEmail, "BILLER");
		String second = invited(guestEmail, "AUDITOR");

		mvc.perform(as(get("/api/v1/invitations"), owner).header(TENANT_HEADER, tenant))
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].role").value("AUDITOR"));
		accept(guest, first).andExpect(status().isNotFound());
		accept(guest, second).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("AUDITOR"));
		// La que quedó sin efecto consta como revocada, y por cuál se cambió.
		assertThat(events("INVITATION_REVOKED")).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT e.metadata ->> 'replacedBy' = (SELECT id::text FROM invitations WHERE tenant_id = ? AND role = 'AUDITOR')
				  FROM audit_events e WHERE e.tenant_id = ? AND e.action = 'INVITATION_REVOKED'
				""", Boolean.class, tenant, tenant)).isTrue();
	}

	@Test
	void nobodyReplacesAnInvitationTheyCouldNotRevoke() throws Exception {
		String code = invited(guestEmail, "ADMIN");

		invite(memberToken(tenant, "ADMIN"), guestEmail, "BILLER").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_NOT_GRANTABLE"));

		// La invitación del propietario sigue en pie.
		accept(guest, code).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
		assertThat(events("INVITATION_REVOKED")).isZero();
	}

	@Test
	void anExpiredInvitationDoesNotStandInTheWayOfANewOne() throws Exception {
		invited(guestEmail, "ADMIN");
		jdbc.update("UPDATE invitations SET expires_at = now() - interval '1 minute' WHERE tenant_id = ?", tenant);

		// Ya no vale ni se ve en la lista: quien puede invitar no necesita que otro la retire antes.
		invite(memberToken(tenant, "ADMIN"), guestEmail, "BILLER").andExpect(status().isCreated());
	}

	@Test
	void thereIsALimitToPendingInvitations() throws Exception {
		jdbc.update("""
				INSERT INTO invitations (tenant_id, email, role, token_hash, invited_by, expires_at)
				SELECT ?, 'relleno-' || n || '@example.test', 'AUDITOR', md5(random()::text) || md5(random()::text),
				       (SELECT user_id FROM memberships WHERE tenant_id = ? LIMIT 1), now() + interval '1 day'
				  FROM generate_series(1, 50) n
				""", tenant, tenant);

		invite(owner, guestEmail, "BILLER").andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("INVITATION_LIMIT_REACHED"));
	}

	@Test
	void anotherCompanySeesAndRevokesNothing() throws Exception {
		invited(guestEmail, "BILLER");
		UUID id = jdbc.queryForObject("SELECT id FROM invitations WHERE tenant_id = ?", UUID.class, tenant);
		String stranger = newUserToken();
		createTenant(stranger);

		mvc.perform(as(get("/api/v1/invitations"), stranger)).andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(as(delete("/api/v1/invitations/" + id), stranger)).andExpect(status().isNotFound());
		mvc.perform(as(get("/api/v1/invitations"), owner).header(TENANT_HEADER, tenant))
			.andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void malformedRequestsAreRejected() throws Exception {
		invite(owner, "no-es-un-correo", "BILLER").andExpect(status().isBadRequest());
		invite(owner, "a@example.test, b@example.test", "BILLER").andExpect(status().isBadRequest());
		mvc.perform(json(post("/api/v1/invitations"), owner, "{\"email\":\"a@example.test\"}").header(TENANT_HEADER, tenant))
			.andExpect(status().isBadRequest());
		accept(guest, "").andExpect(status().isBadRequest());
	}

}
