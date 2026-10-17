package com.example.einvoice.apikey;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Petición autenticada con una API key válida. No lleva el valor de la key ni su hash: solo qué key
 * es, de qué empresa y quién la creó. Sin autoridades: lo que puede hacer lo decide
 * {@link com.example.einvoice.tenancy.TenantAccessResolver}, siempre con el rol BILLER.
 */
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {

	private static final String NAME_PREFIX = "apikey:";

	private final UUID apiKeyId;

	private final UUID tenantId;

	private final UUID createdBy;

	public ApiKeyAuthentication(UUID apiKeyId, UUID tenantId, UUID createdBy) {
		super(List.of());
		this.apiKeyId = apiKeyId;
		this.tenantId = tenantId;
		this.createdBy = createdBy;
		super.setAuthenticated(true);
	}

	public UUID apiKeyId() {
		return apiKeyId;
	}

	public UUID tenantId() {
		return tenantId;
	}

	public UUID createdBy() {
		return createdBy;
	}

	/** Nombre propio de la key: el límite de peticiones le da un cupo aparte del de su creador. */
	@Override
	public String getName() {
		return NAME_PREFIX + apiKeyId;
	}

	@Override
	public Object getPrincipal() {
		return getName();
	}

	/** El secreto ya se comprobó y no se guarda aquí. */
	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public boolean isAuthenticated() {
		return true;
	}

	@Override
	public void setAuthenticated(boolean authenticated) {
		if (!authenticated) {
			throw new IllegalArgumentException("Una autenticación con API key no se puede desmarcar");
		}
	}

}
