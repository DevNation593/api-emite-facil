package com.example.einvoice.tenancy;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.example.einvoice.web.ApiException;

/**
 * Tenant activo de la petición, ya verificado contra las membresías del usuario. Es la única fuente
 * de tenantId para servicios y consultas.
 *
 * @param userId la persona que responde por la petición; con una API key, quien la creó
 * @param apiKeyId la API key con la que llegó la petición, o {@code null} si la hizo una persona
 */
public record TenantAccess(UUID userId, UUID tenantId, Role role, UUID apiKeyId) {

	/** Acceso de una persona, sin API key. */
	public TenantAccess(UUID userId, UUID tenantId, Role role) {
		this(userId, tenantId, role, null);
	}

	public boolean viaApiKey() {
		return apiKeyId != null;
	}

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
