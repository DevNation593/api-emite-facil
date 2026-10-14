package com.example.einvoice.tenancy;

import java.util.List;
import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.example.einvoice.identity.UserIdentity;
import com.example.einvoice.identity.UserIdentityResolver;
import com.example.einvoice.web.ApiException;

/**
 * Resuelve un parámetro {@link TenantAccess}. El tenant se toma de la cabecera X-Tenant-Id solo si
 * el usuario tiene una membresía activa en él; un tenant ajeno y uno inexistente responden igual.
 */
@Component
public class TenantAccessResolver implements HandlerMethodArgumentResolver {

	public static final String HEADER = "X-Tenant-Id";

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
