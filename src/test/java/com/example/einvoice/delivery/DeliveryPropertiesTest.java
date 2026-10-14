package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.example.einvoice.delivery.DeliveryProperties.Email;
import com.example.einvoice.orchestration.RetryPolicy;

class DeliveryPropertiesTest {

	private static DeliveryProperties properties(boolean enabled, String from) {
		return new DeliveryProperties(new Email(enabled, from), Duration.ofMinutes(1), Duration.ofHours(6), 8,
				Duration.ofMinutes(2), 10);
	}

	@Test
	void disabledNeedsNothing() {
		assertThatCode(() -> properties(false, "")).doesNotThrowAnyException();
		assertThatCode(() -> properties(false, null)).doesNotThrowAnyException();
		assertThat(new DeliveryProperties(null, Duration.ofMinutes(1), Duration.ofHours(6), 8, Duration.ofMinutes(2), 10)
			.email()
			.enabled()).isFalse();
	}

	@Test
	void enabledWithoutSenderIsRejected() {
		for (String from : new String[] { null, "", "  ", "no-es-un-correo", "a@b.test\r\nBcc: x@evil.test",
				"Nombre <a@b.test>" }) {
			assertThatThrownBy(() -> properties(true, from)).as("from=%s", from)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("einvoice.delivery.email.from");
		}
		assertThatCode(() -> properties(true, "facturas@plataforma.test")).doesNotThrowAnyException();
	}

	@Test
	void onlyASingleAddressIsValid() {
		assertThat(DeliveryProperties.validAddress("ana.perez+facturas@mi-empresa.com.ec")).isTrue();
		// Sintaxis de grupo y listas: un solo valor que el servidor expandiría a varios destinatarios.
		for (String several : new String[] { "grupo:a@x.test,b@y.test;", "grupo:a@x.test;", "a@x.test,b@y.test",
				"a@x.test;b@y.test", "a@x.test b@y.test", "<a@x.test>", "\"Ana\" <a@x.test>", "a@x.test (Ana)" }) {
			assertThat(DeliveryProperties.validAddress(several)).as(several).isFalse();
		}
	}

	@Test
	void defaultRetriesInsistForMostOfADay() {
		// Valores por defecto de application.yml: 1 minuto que se duplica hasta 6 horas, 12 intentos.
		Duration shortest = Duration.ZERO;
		for (int attempt = 1; attempt < 12; attempt++) {
			shortest = shortest.plus(RetryPolicy.delay(attempt, Duration.ofMinutes(1), Duration.ofHours(6), 0.0));
		}
		assertThat(shortest).isGreaterThan(Duration.ofHours(16));
		// Con 8, el valor inicial, la insistencia no llegaba a dos horas.
		Duration eight = Duration.ZERO;
		for (int attempt = 1; attempt < 8; attempt++) {
			eight = eight.plus(RetryPolicy.delay(attempt, Duration.ofMinutes(1), Duration.ofHours(6), 0.999));
		}
		assertThat(eight).isLessThan(Duration.ofHours(3));
	}

	@Test
	void limitsMustBePositive() {
		Email off = new Email(false, null);
		assertThatThrownBy(() -> new DeliveryProperties(off, Duration.ZERO, Duration.ofHours(6), 8, Duration.ofMinutes(2), 10))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new DeliveryProperties(off, Duration.ofMinutes(1), Duration.ofHours(6), 0, Duration.ofMinutes(2), 10))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new DeliveryProperties(off, Duration.ofMinutes(1), Duration.ofHours(6), 8, Duration.ofMinutes(2), 0))
			.isInstanceOf(IllegalStateException.class);
	}

}
