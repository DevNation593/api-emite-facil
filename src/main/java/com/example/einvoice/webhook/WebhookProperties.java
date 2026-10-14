package com.example.einvoice.webhook;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Avisos a los sistemas de cada empresa. Apagados mientras no se habiliten expresamente: llamar a
 * direcciones que eligen los clientes es una decisión de quien opera.
 *
 * @param allowInsecureHttp solo para desarrollo y pruebas: admite http sin cifrar
 * @param allowPrivateAddresses solo para desarrollo y pruebas: admite direcciones locales o privadas
 * @param sendLease plazo tras el cual un envío sin resultado se considera abandonado
 * @param totalTimeout tope de un intento completo, de la conexión a la línea de estado
 * @param disableAfterFailures intentos fallidos seguidos a partir de los que una suscripción puede apagarse sola
 * @param disableAfterFailingFor tiempo que debe llevar fallando, además, para apagarse: una caída corta
 * con muchos comprobantes no basta
 * @param secretOverlap cuánto sigue firmando el secreto anterior después de rotarlo
 */
@ConfigurationProperties("einvoice.webhooks")
public record WebhookProperties(boolean enabled, boolean allowInsecureHttp, boolean allowPrivateAddresses,
		int maxSubscriptions, Duration retryBackoff, Duration retryMaxBackoff, int retryMaxAttempts, Duration sendLease,
		Duration connectTimeout, Duration totalTimeout, int disableAfterFailures, Duration disableAfterFailingFor,
		Duration secretOverlap) {

	public WebhookProperties {
		requirePositive("retry-backoff", retryBackoff);
		requirePositive("retry-max-backoff", retryMaxBackoff);
		requirePositive("send-lease", sendLease);
		requirePositive("connect-timeout", connectTimeout);
		requirePositive("total-timeout", totalTimeout);
		requirePositive("secret-overlap", secretOverlap);
		requirePositive("disable-after-failing-for", disableAfterFailingFor);
		requireAtLeastOne("max-subscriptions", maxSubscriptions);
		requireAtLeastOne("retry-max-attempts", retryMaxAttempts);
		requireAtLeastOne("disable-after-failures", disableAfterFailures);
		if (sendLease.compareTo(totalTimeout) <= 0) {
			// Si no, un envío todavía en curso se daría por abandonado y saldría dos veces.
			throw new IllegalStateException("einvoice.webhooks.send-lease debe ser mayor que total-timeout");
		}
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException("einvoice.webhooks." + name + " debe ser mayor que cero");
		}
	}

	private static void requireAtLeastOne(String name, int value) {
		if (value < 1) {
			throw new IllegalStateException("einvoice.webhooks." + name + " debe ser al menos 1");
		}
	}

}
