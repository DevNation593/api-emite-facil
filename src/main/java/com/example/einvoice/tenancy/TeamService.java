package com.example.einvoice.tenancy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.web.ApiException;

/**
 * El equipo de una empresa: quién pertenece a ella y con qué rol. El propietario no se toca desde
 * aquí, así que una empresa nunca se queda sin uno.
 */
@Service
public class TeamService {

	public record Member(UUID userId, String email, Role role, OffsetDateTime since, boolean self) {
	}

	private final JdbcClient jdbc;

	private final AuditLog audit;

	public TeamService(JdbcClient jdbc, AuditLog audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<Member> list(TenantAccess access) {
		access.requireWrite();
		return jdbc.sql("""
				SELECT u.id AS user_id, u.email, m.role, m.created_at AS since, u.id = :self AS self
				  FROM memberships m JOIN users u ON u.id = m.user_id
				 WHERE m.tenant_id = :tenant AND m.status = 'ACTIVE'
				 ORDER BY m.created_at, u.id
				""").param("tenant", access.tenantId()).param("self", access.userId()).query(Member.class).list();
	}

	@Transactional
	public Member changeRole(TenantAccess access, UUID userId, Role role) {
		access.requireOwner();
		Role current = lockedRole(access, userId);
		if (current == Role.OWNER) {
			throw ownerProtected();
		}
		if (!access.role().canGrant(role)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_GRANTABLE", "Ese rol no se puede conceder");
		}
		jdbc.sql("UPDATE memberships SET role = :role WHERE tenant_id = :tenant AND user_id = :user")
			.param("role", role.name())
			.param("tenant", access.tenantId())
			.param("user", userId)
			.update();
		audit.record(access.tenantId(), access.userId(), "MEMBER_ROLE_CHANGED", "USER", userId,
				Map.of("from", current.name(), "to", role.name()));
		return list(access).stream().filter(member -> member.userId().equals(userId)).findFirst().orElseThrow();
	}

	@Transactional
	public void remove(TenantAccess access, UUID userId) {
		access.requireWrite();
		Role current = lockedRole(access, userId);
		if (current == Role.OWNER) {
			throw ownerProtected();
		}
		// Solo se retira a quien se le podría haber concedido el rol: un administrador no retira a otro.
		if (!access.role().canGrant(current)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_GRANTABLE", "Su rol no permite retirar a esa persona");
		}
		jdbc.sql("DELETE FROM memberships WHERE tenant_id = :tenant AND user_id = :user")
			.param("tenant", access.tenantId())
			.param("user", userId)
			.update();
		audit.record(access.tenantId(), access.userId(), "MEMBER_REMOVED", "USER", userId, Map.of("role", current.name()));
		// Las invitaciones que dejó pendientes caen con él: si no, podría volver con otra cuenta.
		jdbc.sql("""
				UPDATE invitations SET revoked_at = now()
				 WHERE tenant_id = :tenant AND invited_by = :user AND accepted_at IS NULL AND revoked_at IS NULL
				RETURNING id, role
				""")
			.param("tenant", access.tenantId())
			.param("user", userId)
			.query()
			.listOfRows()
			.forEach(left -> audit.record(access.tenantId(), access.userId(), "INVITATION_REVOKED", "INVITATION",
					(UUID) left.get("id"), Map.of("role", left.get("role"), "reason", "INVITER_REMOVED")));
	}

	/** Rol del miembro, con su membresía bloqueada. Un usuario de otra empresa se trata como inexistente. */
	private Role lockedRole(TenantAccess access, UUID userId) {
		return jdbc.sql("SELECT role FROM memberships WHERE tenant_id = :tenant AND user_id = :user FOR UPDATE")
			.param("tenant", access.tenantId())
			.param("user", userId)
			.query(Role.class)
			.optional()
			.orElseThrow(ApiException::notFound);
	}

	private static ApiException ownerProtected() {
		return new ApiException(HttpStatus.CONFLICT, "OWNER_PROTECTED",
				"El propietario de la empresa no se puede retirar ni cambiar de rol");
	}

}
