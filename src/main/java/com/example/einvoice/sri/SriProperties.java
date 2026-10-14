package com.example.einvoice.sri;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la conexión con el SRI. Las direcciones solo salen de aquí —ningún cliente de la
 * API las envía— y se validan al arrancar: HTTPS y un host oficial, salvo que se permitan
 * expresamente direcciones propias (pruebas con un SRI simulado).
 */
@ConfigurationProperties("einvoice.sri")
public record SriProperties(Set<String> enabledEnvironments, boolean allowCustomEndpoints, Endpoints test,
		Endpoints production, Duration connectTimeout, Duration readTimeout, Duration authorizationDelay,
		Duration retryBackoff, Duration retryMaxBackoff, int retryMaxAttempts) {

	private static final Set<String> OFFICIAL_HOSTS = Set.of("celcer.sri.gob.ec", "cel.sri.gob.ec");

	/** Margen que se añade a los tiempos de espera para dar por muerto un envío sin respuesta. */
	private static final Duration LEASE_MARGIN = Duration.ofSeconds(30);

	public record Endpoints(URI reception, URI authorization) {
	}

	public SriProperties {
		enabledEnvironments = enabledEnvironments == null ? Set.of() : Set.copyOf(enabledEnvironments);
		for (String code : enabledEnvironments) {
			if (!code.equals("1") && !code.equals("2")) {
				throw new IllegalStateException(
						"einvoice.sri.enabled-environments solo admite los ambientes 1 (pruebas) y 2 (producción)");
			}
		}
		check("test", test, allowCustomEndpoints);
		check("production", production, allowCustomEndpoints);
		requirePositive("connect-timeout", connectTimeout);
		requirePositive("read-timeout", readTimeout);
		requirePositive("retry-backoff", retryBackoff);
		requirePositive("retry-max-backoff", retryMaxBackoff);
		if (authorizationDelay == null || authorizationDelay.isNegative()) {
			throw new IllegalStateException("einvoice.sri.authorization-delay no puede ser negativo");
		}
		if (retryMaxAttempts < 1) {
			throw new IllegalStateException("einvoice.sri.retry-max-attempts debe ser al menos 1");
		}
	}

	public boolean enabled(SriEnvironment environment) {
		return enabledEnvironments.contains(environment.code());
	}

	public Endpoints endpoints(SriEnvironment environment) {
		return environment == SriEnvironment.TEST ? test : production;
	}

	/** Tiempo tras el cual un envío que no dejó resultado se da por muerto y hay que reconciliarlo. */
	public Duration sendLease() {
		return connectTimeout.plus(readTimeout).plus(LEASE_MARGIN);
	}

	private static void check(String name, Endpoints endpoints, boolean allowCustom) {
		if (endpoints == null) {
			throw new IllegalStateException("Faltan las direcciones einvoice.sri." + name);
		}
		check(name + ".reception", endpoints.reception(), allowCustom);
		check(name + ".authorization", endpoints.authorization(), allowCustom);
	}

	private static void check(String name, URI uri, boolean allowCustom) {
		String property = "einvoice.sri." + name;
		if (uri == null || uri.getHost() == null || uri.getScheme() == null) {
			throw new IllegalStateException(property + " debe ser una dirección absoluta");
		}
		boolean https = uri.getScheme().equalsIgnoreCase("https");
		if (allowCustom) {
			if (!https && !uri.getScheme().equalsIgnoreCase("http")) {
				throw new IllegalStateException(property + " debe ser una dirección http o https");
			}
			return;
		}
		if (!https || !OFFICIAL_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
				|| uri.getUserInfo() != null) {
			throw new IllegalStateException(property + " debe ser una dirección https de un host oficial del SRI"
					+ " (o activar einvoice.sri.allow-custom-endpoints para un SRI simulado)");
		}
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException("einvoice.sri." + name + " debe ser mayor que cero");
		}
	}

}
