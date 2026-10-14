package com.example.einvoice.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

	private static final Duration BASE = Duration.ofSeconds(15);

	private static final Duration MAX = Duration.ofHours(1);

	@Test
	void growsExponentiallyUpToTheMaximum() {
		long[] expectedSeconds = { 15, 30, 60, 120, 240, 480, 960, 1920, 3600, 3600 };
		for (int attempt = 1; attempt <= expectedSeconds.length; attempt++) {
			assertThat(RetryPolicy.delay(attempt, BASE, MAX, 0.5)).as("intento %d", attempt)
				.isEqualTo(Duration.ofSeconds(expectedSeconds[attempt - 1]));
		}
		assertThat(RetryPolicy.delay(1000, BASE, MAX, 0.5)).isEqualTo(MAX);
		assertThat(RetryPolicy.delay(0, BASE, MAX, 0.5)).isEqualTo(BASE);
	}

	/** Tiempo total que se insiste con un máximo de intentos dado, con las esperas más cortas posibles. */
	private static Duration shortestHorizon(int maxAttempts) {
		Duration total = Duration.ZERO;
		for (int attempt = 1; attempt < maxAttempts; attempt++) {
			total = total.plus(RetryPolicy.delay(attempt, BASE, MAX, 0.0));
		}
		return total;
	}

	@Test
	void defaultAttemptsCoverTheDayTheSriMayTake() {
		// Valor por defecto de einvoice.sri.retry-max-attempts (application.yml).
		assertThat(shortestHorizon(40)).isGreaterThan(Duration.ofHours(24));
		// Con 30, el valor inicial, no se llegaba.
		assertThat(shortestHorizon(30)).isLessThan(Duration.ofHours(24));
	}

	@Test
	void jitterStaysWithinTwentyPercent() {
		assertThat(RetryPolicy.delay(1, BASE, MAX, 0.0)).isEqualTo(Duration.ofSeconds(12));
		assertThat(RetryPolicy.delay(1, BASE, MAX, 0.999)).isGreaterThan(Duration.ofMillis(17_900))
			.isLessThan(Duration.ofSeconds(18));
		assertThat(RetryPolicy.delay(20, BASE, MAX, 0.999)).isLessThan(Duration.ofMinutes(72));
	}

}
