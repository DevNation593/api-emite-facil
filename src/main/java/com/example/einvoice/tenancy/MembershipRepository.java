package com.example.einvoice.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

	List<Membership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

	Optional<Membership> findByUserIdAndTenantId(UUID userId, UUID tenantId);

	/** Tenants en los que el usuario tiene una membresía activa, con su rol en cada uno. */
	@Query("""
			SELECT new com.example.einvoice.tenancy.TenantView(t.id, t.name, t.status, m.role)
			FROM Membership m JOIN Tenant t ON t.id = m.tenantId
			WHERE m.userId = :userId AND m.status = com.example.einvoice.tenancy.MembershipStatus.ACTIVE
			ORDER BY t.name, t.id
			""")
	List<TenantView> findActiveTenantsOf(UUID userId);

}
