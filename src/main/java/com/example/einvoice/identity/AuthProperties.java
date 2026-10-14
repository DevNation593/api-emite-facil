package com.example.einvoice.identity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Autenticación propia: la clave con la que la API firma sus tokens, la dirección de la web (los
 * enlaces de los correos llevan a ella) y los plazos. La aplicación no arranca sin clave o sin web.
 *
 * @param jwtSecret clave de firma de los tokens de acceso (HS256), al menos 32 bytes
 * @param webUrl dirección pública de la aplicación web, sin barra final
 * @param accessTokenTtl vigencia de un token de acceso
 * @param sessionTtl duración máxima de una sesión, por mucho que se renueve
 * @param verificationTtl vigencia del enlace para confirmar el correo
 * @param resetTtl vigencia del enlace para cambiar la contraseña
 * @param maxLoginFailures intentos fallidos seguidos de un correo antes de bloquearlo
 * @param lockout cuánto dura el bloqueo de un correo, y la ventana en que se cuentan sus fallos
 * @param publicRequestsPerMinute peticiones por minuto de una dirección IP a los endpoints públicos
 */
@ConfigurationProperties("einvoice.auth")
public record AuthProperties(String jwtSecret, URI webUrl, Duration accessTokenTtl, Duration sessionTtl,
		Duration verificationTtl, Duration resetTtl, int maxLoginFailures, Duration lockout,
		int publicRequestsPerMinute) {

	/** Emisor y audiencia de los tokens: los emite y los valida esta misma API. */
	public static final String ISSUER = "einvoice-api";

	public static final String AUDIENCE = "einvoice-api";

	static final int MIN_SECRET_BYTES = 32;

	public AuthProperties {
		if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("Falta EINVOICE_JWT_SECRET o tiene menos de " + MIN_SECRET_BYTES
					+ " bytes: es la clave con la que la API firma sus tokens (openssl rand -base64 48)");
		}
		if (webUrl == null || webUrl.getHost() == null
				|| !("https".equals(webUrl.getScheme()) || "http".equals(webUrl.getScheme()))) {
			throw new IllegalStateException("Falta EINVOICE_WEB_URL o no es una dirección http(s) absoluta:"
					+ " es la dirección de la aplicación web, a la que llevan los enlaces de los correos");
		}
		if (webUrl.getRawQuery() != null || webUrl.getRawFragment() != null) {
			throw new IllegalStateException("EINVOICE_WEB_URL no debe llevar parámetros ni fragmento");
		}
		webUrl = URI.create(webUrl.toString().replaceAll("/+$", ""));
		requirePositive("access-token-ttl", accessTokenTtl);
		requirePositive("session-ttl", sessionTtl);
		requirePositive("verification-ttl", verificationTtl);
		requirePositive("reset-ttl", resetTtl);
		requirePositive("lockout", lockout);
		if (maxLoginFailures < 1 || publicRequestsPerMinute < 1) {
			throw new IllegalStateException("einvoice.auth.max-login-failures y public-requests-per-minute deben ser"
					+ " al menos 1");
		}
	}

	byte[] jwtKey() {
		return jwtSecret.getBytes(StandardCharsets.UTF_8);
	}

	/** Enlace de la web con un código de un solo uso: el código va en el fragmento, que no viaja al servidor. */
	URI link(String path, String token) {
		return URI.create(webUrl + path + "#token=" + token);
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException("einvoice.auth." + name + " debe ser positivo");
		}
	}

}
