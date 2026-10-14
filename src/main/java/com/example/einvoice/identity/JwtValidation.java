package com.example.einvoice.identity;

import java.util.List;
import java.util.function.Predicate;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Reglas que debe cumplir un token además de la firma: emisor esperado, vigencia, y además que
 * tenga expiración, sujeto y que esté emitido para esta API. Sin la audiencia, un token que el
 * mismo proveedor emitió para otra aplicación serviría aquí.
 */
public final class JwtValidation {

	private JwtValidation() {
	}

	public static OAuth2TokenValidator<Jwt> validator(String issuer, List<String> audiences) {
		return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
				require(jwt -> jwt.getExpiresAt() != null, "El token no tiene fecha de expiración"),
				require(jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank(), "El token no tiene sujeto"),
				require(jwt -> jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(audiences::contains),
						"El token no está emitido para esta API"));
	}

	private static OAuth2TokenValidator<Jwt> require(Predicate<Jwt> rule, String description) {
		return jwt -> rule.test(jwt) ? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
	}

}
