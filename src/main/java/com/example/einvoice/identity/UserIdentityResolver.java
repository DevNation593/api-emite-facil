package com.example.einvoice.identity;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
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

/** Resuelve un parámetro {@link UserIdentity} a partir del JWT ya validado de la petición. */
@Component
public class UserIdentityResolver implements HandlerMethodArgumentResolver {

	private static final String ATTRIBUTE = UserIdentity.class.getName();

	private final UserDirectory users;

	public UserIdentityResolver(UserDirectory users) {
		this.users = users;
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
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere un token de acceso válido");
		}
		Jwt jwt = token.getToken();
		String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;
		String email = jwt.getClaimAsString("email");
		if (issuer == null || jwt.getSubject() == null) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere un token de acceso válido");
		}
		UserIdentity identity = new UserIdentity(users.resolve(issuer, jwt.getSubject(), email), issuer,
				jwt.getSubject(), email, jwt.getClaimAsBoolean("email_verified"));
		webRequest.setAttribute(ATTRIBUTE, identity, RequestAttributes.SCOPE_REQUEST);
		return identity;
	}

}
