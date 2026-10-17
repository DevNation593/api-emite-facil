package com.example.einvoice.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Sin clave de firma suficiente o sin dirección de la web, la aplicación no arranca. */
class AuthPropertiesTest {

	static final String SECRET = "x".repeat(32);

	static AuthProperties properties(String secret, String webUrl) {
		return new AuthProperties(secret, webUrl == null ? null : URI.create(webUrl), Duration.ofMinutes(15),
				Duration.ofHours(12), Duration.ofHours(24), Duration.ofHours(1), 5, Duration.ofMinutes(15), 20);
	}

	@Test
	void requiresASecretOfAtLeast32Bytes() {
		for (String secret : new String[] { null, "", "x".repeat(31) }) {
			assertThatThrownBy(() -> properties(secret, "https://app.example.com"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("EINVOICE_JWT_SECRET");
		}
		// Cuentan bytes, no caracteres: 16 eñes son 32 bytes.
		assertThat(properties("ñ".repeat(16), "https://app.example.com").jwtKey()).hasSize(32);
	}

	@Test
	void requiresAnAbsoluteWebAddress() {
		for (String url : new String[] { null, "/relativa", "ftp://app.example.com", "https://app.example.com/?a=1" }) {
			assertThatThrownBy(() -> properties(SECRET, url)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("EINVOICE_WEB_URL");
		}
	}

	@Test
	void linksCarryTheCodeInTheFragmentSoItNeverReachesAServer() {
		AuthProperties properties = properties(SECRET, "https://app.example.com/facturas/");
		assertThat(properties.link("/verificar-correo", "abc"))
			.hasToString("https://app.example.com/facturas/verificar-correo#token=abc");
	}

}
