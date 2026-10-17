package com.example.einvoice.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.example.einvoice.identity.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * JWT de prueba firmados como los firma la API: HS256 con la clave de las pruebas (EINVOICE_JWT_SECRET
 * de {@link AbstractIntegrationTest}). Sirven para llegar a cualquier endpoint sin pasar por el ingreso.
 */
public final class TestTokens {

	/** La misma clave que reciben las pruebas en EINVOICE_JWT_SECRET. */
	public static final String SECRET = "clave-de-firma-solo-para-las-pruebas-0123456789";

	private static final String OTHER_SECRET = "otra-clave-de-firma-que-la-api-no-conoce-0123456789";

	private TestTokens() {
	}

	/** Token de acceso válido de ese usuario, en una sesión cualquiera. */
	public static String bearer(UUID userId) {
		return bearer(userId.toString());
	}

	/** Token válido con un sujeto arbitrario, para probar qué pasa con sujetos que no son usuarios. */
	public static String bearer(String subject) {
		return sign(SECRET, claims(AuthProperties.ISSUER, AuthProperties.AUDIENCE, subject, Instant.now().plusSeconds(600)));
	}

	public static String expired(String subject) {
		return sign(SECRET, claims(AuthProperties.ISSUER, AuthProperties.AUDIENCE, subject, Instant.now().minusSeconds(600)));
	}

	public static String wrongIssuer(String subject) {
		return sign(SECRET, claims("https://otro-emisor.test", AuthProperties.AUDIENCE, subject, Instant.now().plusSeconds(600)));
	}

	public static String wrongKey(String subject) {
		return sign(OTHER_SECRET, claims(AuthProperties.ISSUER, AuthProperties.AUDIENCE, subject, Instant.now().plusSeconds(600)));
	}

	public static String wrongAudience(String subject) {
		return sign(SECRET, claims(AuthProperties.ISSUER, "otra-aplicacion", subject, Instant.now().plusSeconds(600)));
	}

	public static String withoutAudience(String subject) {
		return sign(SECRET, claims(AuthProperties.ISSUER, null, subject, Instant.now().plusSeconds(600)));
	}

	public static String withoutExpiry(String subject) {
		return sign(SECRET, claims(AuthProperties.ISSUER, AuthProperties.AUDIENCE, subject, null));
	}

	private static JWTClaimsSet.Builder claims(String issuer, String audience, String subject, Instant expiresAt) {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer)
			.subject(subject)
			.claim("sid", UUID.randomUUID().toString())
			.issueTime(Date.from(Instant.now().minusSeconds(900)));
		if (audience != null) {
			claims.audience(audience);
		}
		if (expiresAt != null) {
			claims.expirationTime(Date.from(expiresAt));
		}
		return claims;
	}

	private static String sign(String secret, JWTClaimsSet.Builder claims) {
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
		try {
			jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
		return "Bearer " + jwt.serialize();
	}

}
