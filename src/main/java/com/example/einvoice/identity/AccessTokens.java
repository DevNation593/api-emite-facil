package com.example.einvoice.identity;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Tokens de acceso de la API: JWT firmados con HS256 y la clave EINVOICE_JWT_SECRET. Los emite y los
 * valida esta misma aplicación, así que la firma simétrica basta. El sujeto es el id del usuario y sid,
 * el de la sesión que lo obtuvo.
 */
public class AccessTokens {

	public static final String SESSION_CLAIM = "sid";

	private final NimbusJwtEncoder encoder;

	private final NimbusJwtDecoder decoder;

	private final AuthProperties properties;

	private final InstantSource clock;

	public AccessTokens(AuthProperties properties, InstantSource clock) {
		SecretKey key = new SecretKeySpec(properties.jwtKey(), "HmacSHA256");
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		this.decoder.setJwtValidator(JwtValidation.validator(AuthProperties.ISSUER, List.of(AuthProperties.AUDIENCE)));
		this.properties = properties;
		this.clock = clock;
	}

	public record Issued(String value, Instant expiresAt) {
	}

	public Issued issue(UUID userId, UUID sessionId, String email, boolean emailVerified) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(AuthProperties.ISSUER)
			.audience(List.of(AuthProperties.AUDIENCE))
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.id(UUID.randomUUID().toString())
			.claim(SESSION_CLAIM, sessionId.toString())
			.claim("email", email)
			.claim("email_verified", emailVerified)
			.build();
		String value = encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
		return new Issued(value, expiresAt);
	}

	public JwtDecoder decoder() {
		return decoder;
	}

}
