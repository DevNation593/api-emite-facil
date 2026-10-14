package com.example.einvoice.tenancy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.identity.UserIdentity;
import com.example.einvoice.web.ApiException;

/**
 * Invitaciones a una empresa. Quien invita recibe un código que comparte por su cuenta; de él solo
 * se guarda la huella. Para aceptarla hay que iniciar sesión con el correo invitado.
 */
@Service
@EnableConfigurationProperties(InvitationProperties.class)
public class InvitationService {

	public record Created(UUID id, String email, Role role, OffsetDateTime expiresAt, String token) {
	}

	public record Pending(UUID id, String email, Role role, OffsetDateTime expiresAt, String invitedBy) {
	}

	public record Preview(String tenantName, Role role, String email, OffsetDateTime expiresAt) {
	}

	private record Row(UUID id, UUID tenantId, String email, Role role, OffsetDateTime expiresAt, String tenantName,
			TenantStatus tenantStatus) {
	}

	private record Stored(UUID id, String email, Role role, OffsetDateTime expiresAt) {
	}

	private record Standing(UUID id, Role role, boolean stillValid) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final InvitationProperties properties;

	public InvitationService(JdbcClient jdbc, AuditLog audit, InvitationProperties properties) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.properties = properties;
	}

	@Transactional
	public Created create(TenantAccess access, String email, Role role) {
		access.requireWrite();
		requireGrantable(access, role);
		String address = email.strip().toLowerCase(Locale.ROOT);
		// Serializa las invitaciones de la empresa: el tope y el reemplazo se deciden de una en una.
		// NO KEY: no detiene a quien, mientras tanto, emite o registra algo de la empresa.
		jdbc.sql("SELECT id FROM tenants WHERE id = :tenant FOR NO KEY UPDATE")
			.param("tenant", access.tenantId())
			.query()
			.listOfRows();
		boolean member = jdbc.sql("""
				SELECT count(*) FROM memberships m JOIN users u ON u.id = m.user_id
				 WHERE m.tenant_id = :tenant AND lower(u.email) = :email
				""").param("tenant", access.tenantId()).param("email", address).query(Long.class).single() > 0;
		if (member) {
			throw new ApiException(HttpStatus.CONFLICT, "ALREADY_MEMBER", "Esa persona ya forma parte de la empresa");
		}
		Optional<Standing> replaced = revokeStanding(access, address);
		long pending = jdbc.sql("""
				SELECT count(*) FROM invitations
				 WHERE tenant_id = :tenant AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()
				""").param("tenant", access.tenantId()).query(Long.class).single();
		if (pending >= properties.maxPending()) {
			throw new ApiException(HttpStatus.CONFLICT, "INVITATION_LIMIT_REACHED",
					"La empresa ya tiene demasiadas invitaciones pendientes; revoque alguna");
		}
		// El código no sale de esta aplicación hacia la base: allí solo llega su huella.
		String token = newToken();
		Stored stored = jdbc.sql("""
				INSERT INTO invitations (tenant_id, email, role, token_hash, invited_by, expires_at)
				VALUES (:tenant, :email, :role, :hash, :user, now() + make_interval(secs => CAST(:seconds AS double precision)))
				RETURNING id, email, role, expires_at
				""")
			.param("tenant", access.tenantId())
			.param("email", address)
			.param("role", role.name())
			.param("hash", hash(token))
			.param("user", access.userId())
			.param("seconds", (double) properties.ttl().toSeconds())
			.query(Stored.class)
			.single();
		// Ni el código ni el correo van a la auditoría: basta con qué se concedió.
		audit.record(access.tenantId(), access.userId(), "INVITATION_CREATED", "INVITATION", stored.id(),
				Map.of("role", role.name()));
		replaced.ifPresent(old -> audit.record(access.tenantId(), access.userId(), "INVITATION_REVOKED", "INVITATION",
				old.id(), Map.of("role", old.role().name(), "replacedBy", stored.id().toString())));
		return new Created(stored.id(), stored.email(), stored.role(), stored.expiresAt(), token);
	}

	/**
	 * Invitar de nuevo al mismo correo deja sin efecto el enlace anterior, pero solo si quien invita
	 * podría haberlo revocado: un administrador no anula así la invitación a ser administrador.
	 */
	private Optional<Standing> revokeStanding(TenantAccess access, String address) {
		Optional<Standing> standing = jdbc.sql("""
				SELECT id, role, expires_at > now() AS still_valid FROM invitations
				 WHERE tenant_id = :tenant AND email = :email AND accepted_at IS NULL AND revoked_at IS NULL FOR UPDATE
				""").param("tenant", access.tenantId()).param("email", address).query(Standing.class).optional();
		standing.ifPresent(old -> {
			// Una caducada ya no vale ni aparece en la lista: no hace falta que otro la retire antes.
			if (old.stillValid() && !access.role().canGrant(old.role())) {
				throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_GRANTABLE",
						"Ese correo ya tiene una invitación pendiente que su rol no permite reemplazar");
			}
			jdbc.sql("UPDATE invitations SET revoked_at = now() WHERE id = :id").param("id", old.id()).update();
		});
		return standing;
	}

	@Transactional(readOnly = true)
	public List<Pending> pending(TenantAccess access) {
		access.requireWrite();
		return jdbc.sql("""
				SELECT i.id, i.email, i.role, i.expires_at, u.email AS invited_by
				  FROM invitations i JOIN users u ON u.id = i.invited_by
				 WHERE i.tenant_id = :tenant AND i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > now()
				 ORDER BY i.created_at DESC, i.id
				""").param("tenant", access.tenantId()).query(Pending.class).list();
	}

	@Transactional
	public void revoke(TenantAccess access, UUID id) {
		access.requireWrite();
		// Una invitación de otra empresa se trata igual que una inexistente.
		Role role = jdbc.sql("""
				SELECT role FROM invitations
				 WHERE id = :id AND tenant_id = :tenant AND accepted_at IS NULL AND revoked_at IS NULL FOR UPDATE
				""").param("id", id).param("tenant", access.tenantId()).query(Role.class).optional()
			.orElseThrow(ApiException::notFound);
		requireGrantable(access, role);
		jdbc.sql("UPDATE invitations SET revoked_at = now() WHERE id = :id").param("id", id).update();
		audit.record(access.tenantId(), access.userId(), "INVITATION_REVOKED", "INVITATION", id,
				Map.of("role", role.name()));
	}

	/** Qué empresa y qué rol ofrece el código, para quien va a aceptarlo. */
	@Transactional(readOnly = true)
	public Preview preview(UserIdentity user, String token) {
		Row row = usable(user, token, false);
		return new Preview(row.tenantName(), row.role(), row.email(), row.expiresAt());
	}

	@Transactional
	public TenantView accept(UserIdentity user, String token) {
		// Bloquea la invitación: dos aceptaciones a la vez dejan una sola membresía.
		Row row = usable(user, token, true);
		boolean member = jdbc.sql("SELECT count(*) FROM memberships WHERE user_id = :user AND tenant_id = :tenant")
			.param("user", user.userId())
			.param("tenant", row.tenantId())
			.query(Long.class)
			.single() > 0;
		if (member) {
			throw new ApiException(HttpStatus.CONFLICT, "ALREADY_MEMBER", "Ya forma parte de esa empresa");
		}
		jdbc.sql("INSERT INTO memberships (user_id, tenant_id, role) VALUES (:user, :tenant, :role)")
			.param("user", user.userId())
			.param("tenant", row.tenantId())
			.param("role", row.role().name())
			.update();
		jdbc.sql("UPDATE invitations SET accepted_at = now(), accepted_by = :user WHERE id = :id")
			.param("user", user.userId())
			.param("id", row.id())
			.update();
		audit.record(row.tenantId(), user.userId(), "INVITATION_ACCEPTED", "INVITATION", row.id(),
				Map.of("role", row.role().name()));
		return new TenantView(row.tenantId(), row.tenantName(), row.tenantStatus(), row.role());
	}

	/**
	 * La invitación vigente de ese código, si quien la presenta es a quien se invitó. Un código que
	 * no existe, caducó, se revocó o ya se usó responde siempre lo mismo.
	 */
	private Row usable(UserIdentity user, String token, boolean lock) {
		Optional<Row> found = jdbc.sql("""
				SELECT i.id, i.tenant_id, i.email, i.role, i.expires_at, t.name AS tenant_name, t.status AS tenant_status
				  FROM invitations i JOIN tenants t ON t.id = i.tenant_id
				 WHERE i.token_hash = :hash AND i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > now()
				""" + (lock ? " FOR UPDATE OF i" : "")).param("hash", hash(token)).query(Row.class).optional();
		Row row = found.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_VALID",
				"La invitación no es válida: el enlace no existe, caducó o ya se usó"));
		if (user.email() == null ? !properties.allowWithoutEmail() : !user.email().strip().equalsIgnoreCase(row.email())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "INVITATION_EMAIL_MISMATCH",
					"La invitación es para otro correo; inicie sesión con la cuenta a la que se envió");
		}
		if (Boolean.FALSE.equals(user.emailVerified())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
					"Verifique su correo en su cuenta antes de aceptar la invitación");
		}
		return row;
	}

	private static void requireGrantable(TenantAccess access, Role role) {
		if (!access.role().canGrant(role)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_GRANTABLE", "Su rol no permite conceder ese rol");
		}
	}

	private static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String hash(String token) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
