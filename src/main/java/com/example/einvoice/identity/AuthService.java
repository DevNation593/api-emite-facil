package com.example.einvoice.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.einvoice.web.ApiException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Cuentas y sesiones. Un ingreso abre una sesión que dura como mucho session-ttl; cada renovación
 * entrega un token de acceso nuevo y cambia el de renovación. Los enlaces de los correos y los tokens de
 * renovación solo se guardan como SHA-256. Las respuestas no revelan si un correo tiene cuenta.
 */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	/** Correos de un mismo tipo que se envían como mucho por usuario y hora. */
	static final int MAX_EMAILS_PER_HOUR = 5;

	private static final String VERIFY_EMAIL = "VERIFY_EMAIL";

	private static final String RESET_PASSWORD = "RESET_PASSWORD";

	private static final SecureRandom RANDOM = new SecureRandom();

	public record Tokens(String accessToken, long expiresIn, String refreshToken, Instant refreshExpiresAt) {
	}

	private record Account(UUID id, String email, String passwordHash, OffsetDateTime emailVerifiedAt) {

		boolean verified() {
			return emailVerifiedAt != null;
		}

	}

	private record RefreshRow(UUID sessionId, OffsetDateTime usedAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt,
			UUID userId, String email, String passwordHash, OffsetDateTime emailVerifiedAt) {
	}

	private final JdbcClient jdbc;

	private final Passwords passwords;

	private final AccessTokens accessTokens;

	private final AuthAttemptLimiter limiter;

	private final AuthProperties properties;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final Counter loginSucceeded;

	private final Counter loginFailed;

	private final Counter loginLocked;

	public AuthService(JdbcClient jdbc, Passwords passwords, AccessTokens accessTokens, AuthAttemptLimiter limiter,
			AuthProperties properties, ApplicationEventPublisher events, TransactionTemplate transactions,
			MeterRegistry registry) {
		this.jdbc = jdbc;
		this.passwords = passwords;
		this.accessTokens = accessTokens;
		this.limiter = limiter;
		this.properties = properties;
		this.events = events;
		this.transactions = transactions;
		this.loginSucceeded = loginCounter(registry, "success");
		this.loginFailed = loginCounter(registry, "failure");
		this.loginLocked = loginCounter(registry, "locked");
	}

	/**
	 * Crea la cuenta y envía el enlace para confirmar el correo. Si el correo ya tiene cuenta no cambia nada:
	 * le llega un aviso con un enlace para cambiar la contraseña, y quien llama ve la misma respuesta.
	 */
	@Transactional
	public void register(String rawEmail, String password) {
		String email = normalize(rawEmail);
		passwords.requireAcceptable("password", password, email);
		Optional<UUID> created = jdbc.sql("""
				INSERT INTO users (email, password_hash) VALUES (:email, :hash)
				ON CONFLICT (email) DO NOTHING
				RETURNING id
				""").param("email", email).param("hash", passwords.hash(password)).query(UUID.class).optional();
		if (created.isPresent()) {
			sendLink(created.get(), email, VERIFY_EMAIL);
			log.info("auth.registered user={}", created.get());
			return;
		}
		account(email).ifPresent(existing -> sendLink(existing.id(), email, "ALREADY_REGISTERED"));
	}

	public Tokens login(String rawEmail, String password, String clientIp) {
		requireIpAllowed(clientIp);
		String email = normalize(rawEmail);
		long locked = limiter.lockedFor(email);
		if (locked > 0) {
			loginLocked.increment();
			throw locked(locked);
		}
		Optional<Account> account = account(email);
		if (!passwords.matches(password, account.map(Account::passwordHash).orElse(null))) {
			long lockedNow = limiter.recordFailure(email);
			loginFailed.increment();
			log.warn("auth.login.failed user={}", account.map(found -> found.id().toString()).orElse("desconocido"));
			if (lockedNow > 0) {
				loginLocked.increment();
				throw locked(lockedNow);
			}
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Correo o contraseña incorrectos");
		}
		limiter.recordSuccess(email);
		Account user = account.get();
		Tokens tokens = transactions.execute(status -> {
			UUID sessionId = jdbc.sql("""
					INSERT INTO auth_sessions (user_id, expires_at)
					VALUES (:user, now() + make_interval(secs => CAST(:seconds AS double precision)))
					RETURNING id
					""").param("user", user.id()).param("seconds", properties.sessionTtl().toSeconds())
				.query(UUID.class).single();
			return open(user, sessionId);
		});
		loginSucceeded.increment();
		log.info("auth.login user={}", user.id());
		return tokens;
	}

	/**
	 * Renueva el token de acceso y cambia el de renovación. Un token de renovación que ya se usó delata que
	 * alguien más lo tiene: se cierra la sesión entera, y ese cierre se guarda aunque la petición falle.
	 */
	public Tokens refresh(String refreshToken) {
		Object outcome = transactions.execute(status -> {
			Optional<RefreshRow> found = jdbc.sql("""
					SELECT t.session_id, t.used_at, s.expires_at, s.revoked_at, u.id AS user_id, u.email,
					       u.password_hash, u.email_verified_at
					  FROM auth_refresh_tokens t
					  JOIN auth_sessions s ON s.id = t.session_id
					  JOIN users u ON u.id = s.user_id
					 WHERE t.token_hash = :hash
					   FOR UPDATE OF t, s
					""").param("hash", sha256(refreshToken)).query(RefreshRow.class).optional();
			if (found.isEmpty() || found.get().revokedAt() != null
					|| !found.get().expiresAt().toInstant().isAfter(Instant.now())) {
				return null;
			}
			RefreshRow row = found.get();
			if (row.usedAt() != null) {
				revoke(row.sessionId(), "TOKEN_REUSED");
				log.warn("auth.refresh.reused user={} session={}", row.userId(), row.sessionId());
				return null;
			}
			jdbc.sql("UPDATE auth_refresh_tokens SET used_at = now() WHERE token_hash = :hash")
				.param("hash", sha256(refreshToken))
				.update();
			jdbc.sql("UPDATE auth_sessions SET last_used_at = now() WHERE id = :id").param("id", row.sessionId()).update();
			return open(new Account(row.userId(), row.email(), row.passwordHash(), row.emailVerifiedAt()),
					row.sessionId());
		});
		if (outcome instanceof Tokens tokens) {
			return tokens;
		}
		throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
				"La sesión caducó o se cerró; vuelva a ingresar");
	}

	/** Cierra la sesión de ese token de renovación. Sin efecto si no existe o ya estaba cerrada. */
	@Transactional
	public void logout(String refreshToken) {
		jdbc.sql("SELECT session_id FROM auth_refresh_tokens WHERE token_hash = :hash")
			.param("hash", sha256(refreshToken))
			.query(UUID.class)
			.optional()
			.ifPresent(session -> revoke(session, "LOGOUT"));
	}

	@Transactional
	public void verifyEmail(String token) {
		UUID userId = consume(token, VERIFY_EMAIL);
		jdbc.sql("UPDATE users SET email_verified_at = now() WHERE id = :id AND email_verified_at IS NULL")
			.param("id", userId)
			.update();
		log.info("auth.email.verified user={}", userId);
	}

	@Transactional
	public void resendVerification(UUID userId) {
		Account account = jdbc.sql("SELECT id, email, password_hash, email_verified_at FROM users WHERE id = :id")
			.param("id", userId)
			.query(Account.class)
			.single();
		if (!account.verified() && account.email() != null) {
			sendLink(account.id(), account.email(), VERIFY_EMAIL);
		}
	}

	/** Envía el enlace para cambiar la contraseña si el correo tiene cuenta; la respuesta es la misma si no. */
	@Transactional
	public void requestPasswordReset(String rawEmail, String clientIp) {
		requireIpAllowed(clientIp);
		String email = normalize(rawEmail);
		account(email).ifPresent(account -> sendLink(account.id(), email, RESET_PASSWORD));
	}

	/**
	 * Pone la contraseña nueva y cierra todas las sesiones. Haber recibido el enlace prueba que el correo es
	 * suyo, así que también queda confirmado.
	 */
	@Transactional
	public void resetPassword(String token, String newPassword) {
		UUID userId = consume(token, RESET_PASSWORD);
		String email = jdbc.sql("SELECT email FROM users WHERE id = :id").param("id", userId).query(String.class).single();
		passwords.requireAcceptable("newPassword", newPassword, email);
		jdbc.sql("""
				UPDATE users SET password_hash = :hash, email_verified_at = COALESCE(email_verified_at, now())
				 WHERE id = :id
				""").param("hash", passwords.hash(newPassword)).param("id", userId).update();
		// Los demás enlaces pendientes para cambiarla dejan de servir.
		jdbc.sql("UPDATE user_tokens SET used_at = now() WHERE user_id = :id AND purpose = :purpose AND used_at IS NULL")
			.param("id", userId)
			.param("purpose", RESET_PASSWORD)
			.update();
		revokeAll(userId, null, "PASSWORD_RESET");
		limiter.recordSuccess(email);
		log.info("auth.password.reset user={}", userId);
	}

	/** Cambia la contraseña conociendo la actual y cierra las demás sesiones; la que hace el cambio sigue. */
	@Transactional
	public void changePassword(UUID userId, UUID currentSession, String currentPassword, String newPassword) {
		Account account = jdbc.sql("SELECT id, email, password_hash, email_verified_at FROM users WHERE id = :id FOR UPDATE")
			.param("id", userId)
			.query(Account.class)
			.single();
		if (!passwords.matches(currentPassword, account.passwordHash())) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CURRENT_PASSWORD",
					"La contraseña actual no es correcta",
					List.of(new ApiException.FieldError("currentPassword", "No es la contraseña actual")));
		}
		passwords.requireAcceptable("newPassword", newPassword, account.email());
		jdbc.sql("UPDATE users SET password_hash = :hash WHERE id = :id")
			.param("hash", passwords.hash(newPassword))
			.param("id", userId)
			.update();
		revokeAll(userId, currentSession, "PASSWORD_CHANGED");
		log.info("auth.password.changed user={}", userId);
	}

	private Tokens open(Account user, UUID sessionId) {
		String refreshToken = randomToken();
		jdbc.sql("INSERT INTO auth_refresh_tokens (token_hash, session_id) VALUES (:hash, :session)")
			.param("hash", sha256(refreshToken))
			.param("session", sessionId)
			.update();
		OffsetDateTime sessionEnds = jdbc.sql("SELECT expires_at FROM auth_sessions WHERE id = :id")
			.param("id", sessionId)
			.query(OffsetDateTime.class)
			.single();
		AccessTokens.Issued access = accessTokens.issue(user.id(), sessionId, user.email(), user.verified());
		long expiresIn = Math.max(1, Duration.between(Instant.now(), access.expiresAt()).toSeconds());
		return new Tokens(access.value(), expiresIn, refreshToken, sessionEnds.toInstant());
	}

	private void revoke(UUID sessionId, String reason) {
		jdbc.sql("UPDATE auth_sessions SET revoked_at = now(), revoke_reason = :reason WHERE id = :id AND revoked_at IS NULL")
			.param("reason", reason)
			.param("id", sessionId)
			.update();
	}

	private void revokeAll(UUID userId, UUID except, String reason) {
		jdbc.sql("""
				UPDATE auth_sessions SET revoked_at = now(), revoke_reason = :reason
				 WHERE user_id = :user AND revoked_at IS NULL AND (CAST(:except AS uuid) IS NULL OR id <> :except)
				""").param("reason", reason).param("user", userId).param("except", except).update();
	}

	/**
	 * Crea un enlace de un solo uso y pide el correo. Más de MAX_EMAILS_PER_HOUR por usuario y tipo en una
	 * hora no salen: nadie puede usar la API para inundar el buzón de otro.
	 */
	private void sendLink(UUID userId, String email, String kind) {
		String purpose = VERIFY_EMAIL.equals(kind) ? VERIFY_EMAIL : RESET_PASSWORD;
		int recent = jdbc.sql("""
				SELECT count(*) FROM user_tokens
				 WHERE user_id = :user AND purpose = :purpose AND created_at > now() - interval '1 hour'
				""").param("user", userId).param("purpose", purpose).query(Integer.class).single();
		if (recent >= MAX_EMAILS_PER_HOUR) {
			log.warn("auth.email.throttled user={} kind={}", userId, kind);
			return;
		}
		Duration validFor = VERIFY_EMAIL.equals(purpose) ? properties.verificationTtl() : properties.resetTtl();
		String token = randomToken();
		jdbc.sql("""
				INSERT INTO user_tokens (user_id, purpose, token_hash, expires_at)
				VALUES (:user, :purpose, :hash, now() + make_interval(secs => CAST(:seconds AS double precision)))
				""").param("user", userId).param("purpose", purpose).param("hash", sha256(token))
			.param("seconds", validFor.toSeconds())
			.update();
		events.publishEvent(switch (kind) {
			case VERIFY_EMAIL -> AuthMailer.verification(email, properties.link("/verify-email", token), validFor);
			case RESET_PASSWORD -> AuthMailer.passwordReset(email, properties.link("/reset-password", token),
					validFor);
			default -> AuthMailer.alreadyRegistered(email, properties.link("/reset-password", token), validFor);
		});
	}

	/** Usa un enlace de un solo uso: devuelve su usuario o rechaza con 400 si no vale. */
	private UUID consume(String token, String purpose) {
		Optional<UUID> userId = jdbc.sql("""
				UPDATE user_tokens SET used_at = now()
				 WHERE token_hash = :hash AND purpose = :purpose AND used_at IS NULL AND expires_at > now()
				RETURNING user_id
				""").param("hash", sha256(token)).param("purpose", purpose).query(UUID.class).optional();
		return userId.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LINK",
				"El enlace no es válido, ya se usó o caducó; pida uno nuevo"));
	}

	private Optional<Account> account(String email) {
		return jdbc.sql("SELECT id, email, password_hash, email_verified_at FROM users WHERE email = :email")
			.param("email", email)
			.query(Account.class)
			.optional();
	}

	private void requireIpAllowed(String clientIp) {
		long retryAfter = limiter.ipRetryAfter(clientIp == null ? "desconocida" : clientIp);
		if (retryAfter > 0) {
			throw ApiException.tooManyRequests("RATE_LIMITED", "Demasiados intentos en poco tiempo; espere un momento",
					retryAfter);
		}
	}

	private static ApiException locked(long seconds) {
		return ApiException.tooManyRequests("ACCOUNT_LOCKED",
				"Demasiados intentos fallidos con este correo; espere unos minutos o cambie la contraseña", seconds);
	}

	static String normalize(String email) {
		return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String sha256(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Counter loginCounter(MeterRegistry registry, String result) {
		return Counter.builder("einvoice.auth.logins")
			.description("Intentos de ingreso con correo y contraseña, por resultado")
			.tag("result", result)
			.register(registry);
	}

}
