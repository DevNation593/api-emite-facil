package com.example.einvoice.orchestration;

import java.time.Duration;

/** Espera entre reintentos (ante el SRI o al entregar): se duplica en cada intento hasta un máximo, con variación. */
public final class RetryPolicy {

	/** Variación máxima alrededor de la espera calculada, para que los reintentos no coincidan. */
	private static final double JITTER = 0.2;

	private RetryPolicy() {
	}

	/**
	 * @param attempt número del intento que acaba de fallar, desde 1
	 * @param jitter01 valor en [0, 1) que elige la variación: 0 da un 20 % menos y casi 1 un 20 % más
	 */
	public static Duration delay(int attempt, Duration base, Duration max, double jitter01) {
		int doublings = Math.max(0, attempt - 1);
		long millis = max.toMillis();
		// Más de 40 duplicaciones desbordan cualquier base razonable: ya es el máximo.
		if (doublings < 40) {
			long candidate = base.toMillis() << doublings;
			if (candidate > 0 && (candidate >> doublings) == base.toMillis()) {
				millis = Math.min(candidate, max.toMillis());
			}
		}
		double factor = 1 - JITTER + 2 * JITTER * jitter01;
		return Duration.ofMillis(Math.round(millis * factor));
	}

}
