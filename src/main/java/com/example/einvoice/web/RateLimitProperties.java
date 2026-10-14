package com.example.einvoice.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cuántas peticiones puede hacer un usuario por minuto: en total y, dentro de ese total, cuántas
 * que cambian datos.
 */
@ConfigurationProperties("einvoice.rate-limit")
public record RateLimitProperties(int requestsPerMinute, int writesPerMinute) {

	public RateLimitProperties {
		if (requestsPerMinute < 1 || writesPerMinute < 1) {
			throw new IllegalArgumentException("einvoice.rate-limit: los límites deben ser al menos 1");
		}
	}

}
