package com.example.einvoice.identity;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.example.einvoice.web.ApiException;

/**
 * Resuelve un parámetro {@link UserIdentity} a partir del JWT ya validado de la petición. El sujeto es
 * el id del usuario; se lee de la base para tener su correo y su verificación al día.
 */
@Component
public class UserIdentityResolver implements HandlerMethodArgumentResolver {

	private static final String ATTRIBUTE = UserIdentity.class.getName();

	private record Row(UUID id, String email, OffsetDateTime emailVerifiedAt) {
	}

	private final JdbcClient jdbc;

	public UserIdentityResolver(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType().equals(UserIdentity.class);
	}

	@Override
	public UserIdentity resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		return resolve(webRequest);
	}

	/** Una sola resolución por petición, aunque varios parámetros la necesiten. */
	public UserIdentity resolve(NativeWebRequest webRequest) {
		Object cached = webRequest.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (cached instanceof UserIdentity identity) {
			return identity;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken token)) {
			throw unauthenticated();
		}
		Jwt jwt = token.getToken();
		UUID userId = uuid(jwt.getSubject()).orElseThrow(UserIdentityResolver::unauthenticated);
		UUID sessionId = uuid(jwt.getClaimAsString(AccessTokens.SESSION_CLAIM))
			.orElseThrow(UserIdentityResolver::unauthenticated);
		// Un token de un usuario que ya no existe no sirve, por bien firmado que esté.
		Row row = jdbc.sql("SELECT id, email, email_verified_at FROM users WHERE id = :id")
			.param("id", userId)
			.query(Row.class)
			.optional()
			.orElseThrow(UserIdentityResolver::unauthenticated);
		UserIdentity identity = new UserIdentity(row.id(), sessionId, row.email(), row.emailVerifiedAt() != null);
		webRequest.setAttribute(ATTRIBUTE, identity, RequestAttributes.SCOPE_REQUEST);
		return identity;
	}

	private static Optional<UUID> uuid(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			return Optional.empty();
		}
	}

	private static ApiException unauthenticated() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere un token de acceso válido");
	}

}
