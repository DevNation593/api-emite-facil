package com.example.einvoice.tenancy;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.example.einvoice.web.ApiException;

/**
 * Tenant activo de la petición, ya verificado contra las membresías del usuario. Es la única fuente
 * de tenantId para servicios y consultas.
 */
public record TenantAccess(UUID userId, UUID tenantId, Role role) {

	public void requireWrite() {
		if (!role.canWrite()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "El rol no permite esta operación");
		}
	}

	/** Decisiones que comprometen a la empresa ante el SRI: solo su propietario. */
	public void requireOwner() {
		if (role != Role.OWNER) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "El rol no permite esta operación");
		}
	}

	public void requireBilling() {
		if (!role.canBill()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "El rol no permite esta operación");
		}
	}

}
