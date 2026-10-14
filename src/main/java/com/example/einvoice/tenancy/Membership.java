package com.example.einvoice.tenancy;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "memberships")
public class Membership {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID userId;

	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	private Role role;

	@Enumerated(EnumType.STRING)
	private MembershipStatus status;

	protected Membership() {
	}

	public Membership(UUID userId, UUID tenantId, Role role) {
		this.userId = userId;
		this.tenantId = tenantId;
		this.role = role;
		this.status = MembershipStatus.ACTIVE;
	}

	public UUID getUserId() {
		return userId;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public Role getRole() {
		return role;
	}

	public MembershipStatus getStatus() {
		return status;
	}

}
