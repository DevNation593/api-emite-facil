package com.example.einvoice.tenancy;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.identity.UserIdentity;
import com.example.einvoice.web.ApiException;

@Service
public class TenantService {

	private final TenantRepository tenants;

	private final MembershipRepository memberships;

	private final AuditLog audit;

	public TenantService(TenantRepository tenants, MembershipRepository memberships, AuditLog audit) {
		this.tenants = tenants;
		this.memberships = memberships;
		this.audit = audit;
	}

	/** Crea el tenant y deja a quien lo crea como su propietario. */
	@Transactional
	public TenantView create(UserIdentity user, String name) {
		Tenant tenant = tenants.saveAndFlush(new Tenant(name.strip()));
		memberships.saveAndFlush(new Membership(user.userId(), tenant.getId(), Role.OWNER));
		audit.record(tenant.getId(), user.userId(), "TENANT_CREATED", "TENANT", tenant.getId(),
				Map.of("name", tenant.getName()));
		return new TenantView(tenant.getId(), tenant.getName(), tenant.getStatus(), Role.OWNER);
	}

	@Transactional(readOnly = true)
	public List<TenantView> listFor(UserIdentity user) {
		return memberships.findActiveTenantsOf(user.userId());
	}

	@Transactional(readOnly = true)
	public TenantView current(TenantAccess access) {
		Tenant tenant = tenants.findById(access.tenantId()).orElseThrow(ApiException::notFound);
		return new TenantView(tenant.getId(), tenant.getName(), tenant.getStatus(), access.role());
	}

}
