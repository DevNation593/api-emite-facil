package com.example.einvoice.tenancy;

import java.util.List;
import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.example.einvoice.apikey.ApiKeyAuthentication;
import com.example.einvoice.identity.UserIdentity;
import com.example.einvoice.identity.UserIdentityResolver;
import com.example.einvoice.web.ApiException;

/**
 * Resuelve un parámetro {@link TenantAccess}. El tenant se toma de la cabecera X-Tenant-Id solo si
 * el usuario tiene una membresía activa en él; un tenant ajeno y uno inexistente responden igual. Una
 * API key no pasa por el usuario: su empresa es la de la key.
 */
@Component
public class TenantAccessResolver implements HandlerMethodArgumentResolver {

	public static final String HEADER = "X-Tenant-Id";

	/** Rol con el que actúa una API key: factura y consulta, pero no toca la configuración. */
	public static final Role API_KEY_ROLE = Role.BILLER;

	private final UserIdentityResolver users;

	private final MembershipRepository memberships;

	public TenantAccessResolver(UserIdentityResolver users, MembershipRepository memberships) {
		this.users = users;
		this.memberships = memberships;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType().equals(TenantAccess.class);
	}

	@Override
	public TenantAccess resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		if (SecurityContextHolder.getContext().getAuthentication() instanceof ApiKeyAuthentication key) {
			return forApiKey(key, webRequest.getHeader(HEADER));
		}
		UserIdentity user = users.resolve(webRequest);
		List<TenantView> usable = memberships.findActiveTenantsOf(user.userId())
			.stream()
			.filter(tenant -> tenant.status() == TenantStatus.ACTIVE)
			.toList();
		String header = webRequest.getHeader(HEADER);
		if (header == null || header.isBlank()) {
			return withoutHeader(user, usable);
		}
		UUID requested = parse(header);
		return usable.stream()
			.filter(tenant -> tenant.id().equals(requested))
			.findFirst()
			.map(tenant -> new TenantAccess(user.userId(), tenant.id(), tenant.role()))
			.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "TENANT_FORBIDDEN",
					"Sin acceso al tenant indicado"));
	}

	/**
	 * La empresa es la de la key; X-Tenant-Id es opcional y, si viene, tiene que ser esa. La key solo
	 * sirve mientras la empresa esté activa y su creador siga en ella con un rol que factura; si no,
	 * responde como una key inválida. El rol es siempre {@link #API_KEY_ROLE}, sea cual sea el del creador.
	 */
	private TenantAccess forApiKey(ApiKeyAuthentication key, String header) {
		if (header != null && !header.isBlank() && !parse(header).equals(key.tenantId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "TENANT_FORBIDDEN", "Sin acceso al tenant indicado");
		}
		boolean creatorCanBill = memberships.findActiveTenantsOf(key.createdBy())
			.stream()
			.anyMatch(tenant -> tenant.id().equals(key.tenantId()) && tenant.status() == TenantStatus.ACTIVE
					&& tenant.role().canBill());
		if (!creatorCanBill) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere un token de acceso válido");
		}
		return new TenantAccess(key.createdBy(), key.tenantId(), API_KEY_ROLE, key.apiKeyId());
	}

	private static TenantAccess withoutHeader(UserIdentity user, List<TenantView> usable) {
		if (usable.isEmpty()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "TENANT_FORBIDDEN",
					"El usuario no pertenece a ningún tenant activo");
		}
		if (usable.size() > 1) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED",
					"El usuario pertenece a varios tenants; indique uno en la cabecera " + HEADER);
		}
		TenantView only = usable.get(0);
		return new TenantAccess(user.userId(), only.id(), only.role());
	}

	private static UUID parse(String header) {
		try {
			return UUID.fromString(header.strip());
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "TENANT_HEADER_INVALID",
					"La cabecera " + HEADER + " debe ser un UUID");
		}
	}

}
