package com.example.einvoice.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.SmtpStub;
import com.jayway.jsonpath.JsonPath;

/** Cuentas con correo y contraseña: registro, confirmación, ingreso, renovación, salida y recuperación. */
class AuthApiIT extends AbstractIntegrationTest {

	private static final String PASSWORD = "una contraseña larga";

	private static final Pattern LINK = Pattern.compile("http://web\\.test(/[a-z-]+)#token=([A-Za-z0-9_-]+)");

	record Session(String accessToken, String refreshToken) {

		String bearer() {
			return "Bearer " + accessToken;
		}

	}

	record Link(String path, String token) {
	}

	static String newEmail() {
		return "u-" + UUID.randomUUID() + "@example.test";
	}

	ResultActions send(String path, String body) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	ResultActions register(String email, String password) throws Exception {
		return send("/api/v1/auth/register", credentials(email, password));
	}

	ResultActions login(String email, String password) throws Exception {
		return send("/api/v1/auth/login", credentials(email, password));
	}

	Session session(String email, String password) throws Exception {
		String body = login(email, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new Session(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
	}

	ResultActions refresh(String refreshToken) throws Exception {
		return send("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
	}

	static String credentials(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}

	/** Espera el correo número n (desde 1) que llega a esa dirección y saca su enlace. */
	static Link link(String email, int n) throws Exception {
		await().atMost(Duration.ofSeconds(10)).until(() -> SmtpStub.instance().messages(email).size() >= n);
		MimeMessage message = SmtpStub.instance().messages(email).get(n - 1);
		Matcher matcher = LINK.matcher(message.getContent().toString().replace("=\r\n", ""));
		assertThat(matcher.find()).as("el correo trae un enlace a la web").isTrue();
		return new Link(matcher.group(1), matcher.group(2));
	}

	static String subject(String email, int n) throws Exception {
		return SmtpStub.instance().messages(email).get(n - 1).getSubject();
	}

	@Test
	void anAccountIsCreatedConfirmedAndUsed() throws Exception {
		String email = newEmail();
		register(email, PASSWORD).andExpect(status().isAccepted());

		// Se puede entrar antes de confirmar el correo.
		Session session = session(email, PASSWORD);
		mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.emailVerified").value(false));

		Link link = link(email, 1);
		assertThat(link.path()).isEqualTo("/verify-email");
		assertThat(subject(email, 1)).isEqualTo("Confirma tu correo");
		send("/api/v1/auth/email-verification", "{\"token\":\"" + link.token() + "\"}").andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(jsonPath("$.emailVerified").value(true));
		// El enlace sirve una vez.
		send("/api/v1/auth/email-verification", "{\"token\":\"" + link.token() + "\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("INVALID_LINK"));

		// El token de acceso sirve para el resto de la API.
		createTenant(session.bearer());
		// Solo se guarda el hash de la contraseña, y es Argon2id.
		assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email))
			.startsWith("$argon2id$")
			.doesNotContain(PASSWORD);
	}

	@Test
	void theLoginAnswersWithBothTokensAndTheirLifetimes() throws Exception {
		String email = newEmail();
		register(email, PASSWORD);
		login(email, PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isString())
			.andExpect(jsonPath("$.refreshToken").isString())
			.andExpect(jsonPath("$.expiresIn", greaterThan(800)))
			.andExpect(jsonPath("$.refreshExpiresAt").isString());
	}

	@Test
	void registeringAnEmailThatAlreadyHasAnAccountRevealsNothingAndChangesNothing() throws Exception {
		String email = newEmail();
		register(email, PASSWORD).andExpect(status().isAccepted());
		register(email, "otra contraseña larga").andExpect(status().isAccepted());

		link(email, 2);
		assertThat(subject(email, 2)).isEqualTo("Ya tienes una cuenta");
		login(email, "otra contraseña larga").andExpect(status().isUnauthorized());
		login(email, PASSWORD).andExpect(status().isOk());
	}

	@Test
	void aWrongPasswordAndAnUnknownEmailLookTheSame() throws Exception {
		String email = newEmail();
		register(email, PASSWORD);

		String wrongPassword = login(email, "no es la contraseña").andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		String unknown = login(newEmail(), PASSWORD).andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();

		assertThat(JsonPath.<String>read(wrongPassword, "$.errorCode")).isEqualTo("INVALID_CREDENTIALS");
		assertThat(JsonPath.<String>read(unknown, "$.errorCode")).isEqualTo("INVALID_CREDENTIALS");
		assertThat(JsonPath.<String>read(unknown, "$.detail")).isEqualTo(JsonPath.read(wrongPassword, "$.detail"));
	}

	@Test
	void eachRenewalChangesTheRefreshTokenAndAReusedOneClosesTheSession() throws Exception {
		String email = newEmail();
		register(email, PASSWORD);
		Session first = session(email, PASSWORD);

		String body = refresh(first.refreshToken()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String second = JsonPath.read(body, "$.refreshToken");
		assertThat(second).isNotEqualTo(first.refreshToken());
		mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + JsonPath.read(body, "$.accessToken")))
			.andExpect(status().isOk());

		// Alguien presenta el token ya usado: se cierra la sesión, también para quien tiene el bueno.
		refresh(first.refreshToken()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.errorCode").value("INVALID_REFRESH_TOKEN"));
		refresh(second).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("""
				SELECT revoke_reason FROM auth_sessions s JOIN users u ON u.id = s.user_id WHERE u.email = ?
				""", String.class, email)).isEqualTo("TOKEN_REUSED");
	}

	@Test
	void loggingOutClosesTheSession() throws Exception {
		String email = newEmail();
		register(email, PASSWORD);
		Session session = session(email, PASSWORD);

		send("/api/v1/auth/logout", "{\"refreshToken\":\"" + session.refreshToken() + "\"}").andExpect(status().isNoContent());
		refresh(session.refreshToken()).andExpect(status().isUnauthorized());
		// Salir dos veces, o con un token desconocido, no es un error.
		send("/api/v1/auth/logout", "{\"refreshToken\":\"" + session.refreshToken() + "\"}").andExpect(status().isNoContent());
		send("/api/v1/auth/logout", "{\"refreshToken\":\"desconocido\"}").andExpect(status().isNoContent());
	}

	@Test
	void aForgottenPasswordIsReplacedWithTheEmailedLinkAndEverySessionCloses() throws Exception {
		String email = newEmail();
		register(email, PASSWORD);
		Session open = session(email, PASSWORD);

		send("/api/v1/auth/password-reset/request", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
		Link link = link(email, 2);
		assertThat(link.path()).isEqualTo("/reset-password");
		send("/api/v1/auth/password-reset", "{\"token\":\"" + link.token() + "\",\"newPassword\":\"corta\"}")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("WEAK_PASSWORD"));
		// Un intento con una contraseña rechazada no gasta el enlace.
		send("/api/v1/auth/password-reset", "{\"token\":\"" + link.token() + "\",\"newPassword\":\"la contraseña nueva\"}")
			.andExpect(status().isNoContent());

		login(email, PASSWORD).andExpect(status().isUnauthorized());
		login(email, "la contraseña nueva").andExpect(status().isOk());
		refresh(open.refreshToken()).andExpect(status().isUnauthorized());
		send("/api/v1/auth/password-reset", "{\"token\":\"" + link.token() + "\",\"newPassword\":\"otra contraseña más\"}")
			.andExpect(status().isBadRequest());
		// Recibir el enlace demuestra que el correo es suyo.
		assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE email = ?", Boolean.class,
				email)).isTrue();
	}

	@Test
	void askingToResetThePasswordOfAnUnknownEmailLooksTheSameAndSendsNothing() throws Exception {
		String email = newEmail();
		send("/api/v1/auth/password-reset/request", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
		Thread.sleep(500);
		assertThat(SmtpStub.instance().messages(email)).isEmpty();
	}

	@Test
	void changingThePasswordKeepsThisSessionAndClosesTheOthers() throws Exception {
		String email = newEmail();
		register(email, PASSWORD);
		Session here = session(email, PASSWORD);
		Session elsewhere = session(email, PASSWORD);

		mvc.perform(post("/api/v1/auth/password")
			.header(HttpHeaders.AUTHORIZATION, here.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\":\"no es\",\"newPassword\":\"la contraseña nueva\"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("INVALID_CURRENT_PASSWORD"));
		mvc.perform(post("/api/v1/auth/password")
			.header(HttpHeaders.AUTHORIZATION, here.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"la contraseña nueva\"}"))
			.andExpect(status().isNoContent());

		refresh(here.refreshToken()).andExpect(status().isOk());
		refresh(elsewhere.refreshToken()).andExpect(status().isUnauthorized());
		login(email, "la contraseña nueva").andExpect(status().isOk());
	}

	@Test
	void repeatedFailuresLockTheEmailForAWhile() throws Exception {
		String email = newEmail();
		register(email, PASSWORD);
		for (int attempt = 1; attempt < 5; attempt++) {
			login(email, "no es la contraseña").andExpect(status().isUnauthorized());
		}
		login(email, "no es la contraseña").andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.errorCode").value("ACCOUNT_LOCKED"));
		// Ni con la contraseña buena, mientras dure el bloqueo.
		login(email, PASSWORD).andExpect(status().isTooManyRequests());
	}

	@Test
	void weakPasswordsAreRefused() throws Exception {
		String email = newEmail();
		register(email, "corta").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("WEAK_PASSWORD"))
			.andExpect(jsonPath("$.errors[0].field").value("password"));
		register(email, email).andExpect(status().isUnprocessableEntity());
		register(email, "x".repeat(129)).andExpect(status().isUnprocessableEntity());
		login(email, "corta").andExpect(status().isUnauthorized());
	}

	@Test
	void theConfirmationLinkCanBeSentAgainOnlyWithASession() throws Exception {
		String email = newEmail();
		register(email, PASSWORD);
		Session session = session(email, PASSWORD);
		link(email, 1);

		mvc.perform(post("/api/v1/auth/email-verification/resend"))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/auth/email-verification/resend")
			.header(HttpHeaders.AUTHORIZATION, session.bearer())).andExpect(status().isAccepted());
		Link again = link(email, 2);
		send("/api/v1/auth/email-verification", "{\"token\":\"" + again.token() + "\"}").andExpect(status().isNoContent());
	}

	@Test
	void noMoreThanAFewEmailsPerHourReachTheSameInbox() throws Exception {
		String email = newEmail();
		register(email, PASSWORD);
		for (int request = 0; request < AuthService.MAX_EMAILS_PER_HOUR + 3; request++) {
			send("/api/v1/auth/password-reset/request", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
		}
		link(email, 1 + AuthService.MAX_EMAILS_PER_HOUR);
		Thread.sleep(500);
		List<MimeMessage> received = SmtpStub.instance().messages(email);
		assertThat(received).hasSize(1 + AuthService.MAX_EMAILS_PER_HOUR);
	}

}
