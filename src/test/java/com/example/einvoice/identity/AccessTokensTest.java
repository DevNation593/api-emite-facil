package com.example.einvoice.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class AccessTokensTest {

	final AuthProperties properties = AuthPropertiesTest.properties("clave-de-firma-de-esta-prueba-0123456789",
			"https://app.example.com");

	@Test
	void theApiReadsBackWhatItIssues() {
		AccessTokens tokens = new AccessTokens(properties, InstantSource.system());
		UUID user = UUID.randomUUID();
		UUID session = UUID.randomUUID();
		AccessTokens.Issued issued = tokens.issue(user, session, "a@b.ec", true);

		Jwt jwt = tokens.decoder().decode(issued.value());
		assertThat(jwt.getSubject()).isEqualTo(user.toString());
		assertThat(jwt.getClaimAsString(AccessTokens.SESSION_CLAIM)).isEqualTo(session.toString());
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(AuthProperties.ISSUER);
		assertThat(jwt.getAudience()).containsExactly(AuthProperties.AUDIENCE);
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
		assertThat(issued.expiresAt()).isCloseTo(Instant.now().plus(properties.accessTokenTtl()), byMinute());
	}

	@Test
	void aTokenFromAnotherKeyOrAlreadyExpiredIsRejected() {
		AccessTokens other = new AccessTokens(AuthPropertiesTest.properties("otra-clave-de-firma-distinta-0123456789",
				"https://app.example.com"), InstantSource.system());
		AccessTokens tokens = new AccessTokens(properties, InstantSource.system());
		String foreign = other.issue(UUID.randomUUID(), UUID.randomUUID(), "a@b.ec", false).value();
		assertThatThrownBy(() -> tokens.decoder().decode(foreign)).isInstanceOf(JwtException.class);

		AccessTokens past = new AccessTokens(properties, InstantSource.fixed(Instant.now().minus(1, ChronoUnit.HOURS)));
		String expired = past.issue(UUID.randomUUID(), UUID.randomUUID(), "a@b.ec", false).value();
		assertThatThrownBy(() -> tokens.decoder().decode(expired)).isInstanceOf(JwtException.class);
	}

	private static org.assertj.core.data.TemporalUnitOffset byMinute() {
		return new org.assertj.core.data.TemporalUnitWithinOffset(1, ChronoUnit.MINUTES);
	}

}
