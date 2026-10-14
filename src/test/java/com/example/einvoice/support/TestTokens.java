package com.example.einvoice.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Emite JWT de prueba firmados con una clave RSA generada en memoria; ningún proveedor real interviene. */
public final class TestTokens {

	public static final String ISSUER = "https://idp.test";

	public static final String AUDIENCE = "einvoice-api";

	private static final KeyPair KEY = generate();

	private static final KeyPair OTHER_KEY = generate();

	private TestTokens() {
	}

	public static RSAPublicKey publicKey() {
		return (RSAPublicKey) KEY.getPublic();
	}

	public static String bearer(String subject) {
		return bearer(subject, subject + "@example.test");
	}

	public static String bearer(String subject, String email) {
		return sign(KEY, claims(ISSUER, AUDIENCE, subject, Instant.now().plusSeconds(600)).claim("email", email));
	}

	/** Token cuyo proveedor dice si el correo está verificado. */
	public static String bearer(String subject, String email, boolean emailVerified) {
		return sign(KEY, claims(ISSUER, AUDIENCE, subject, Instant.now().plusSeconds(600)).claim("email", email)
			.claim("email_verified", emailVerified));
	}

	/** Token de un proveedor que no informa del correo del usuario. */
	public static String bearerWithoutEmail(String subject) {
		return sign(KEY, claims(ISSUER, AUDIENCE, subject, Instant.now().plusSeconds(600)));
	}

	public static String expired(String subject) {
		return sign(KEY, claims(ISSUER, AUDIENCE, subject, Instant.now().minusSeconds(600)));
	}

	public static String wrongIssuer(String subject) {
		return sign(KEY, claims("https://otro-idp.test", AUDIENCE, subject, Instant.now().plusSeconds(600)));
	}

	public static String wrongKey(String subject) {
		return sign(OTHER_KEY, claims(ISSUER, AUDIENCE, subject, Instant.now().plusSeconds(600)));
	}

	/** Token legítimo del mismo emisor, pero emitido para otra aplicación. */
	public static String wrongAudience(String subject) {
		return sign(KEY, claims(ISSUER, "otra-aplicacion", subject, Instant.now().plusSeconds(600)));
	}

	public static String withoutAudience(String subject) {
		return sign(KEY, claims(ISSUER, null, subject, Instant.now().plusSeconds(600)));
	}

	public static String withoutExpiry(String subject) {
		return sign(KEY, claims(ISSUER, AUDIENCE, subject, null));
	}

	private static JWTClaimsSet.Builder claims(String issuer, String audience, String subject, Instant expiresAt) {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer)
			.subject(subject)
			.issueTime(Date.from(Instant.now().minusSeconds(900)));
		if (audience != null) {
			claims.audience(audience);
		}
		if (expiresAt != null) {
			claims.expirationTime(Date.from(expiresAt));
		}
		return claims;
	}

	private static String sign(KeyPair key, JWTClaimsSet.Builder claims) {
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
		try {
			jwt.sign(new RSASSASigner(key.getPrivate()));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
		return "Bearer " + jwt.serialize();
	}

	private static KeyPair generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
