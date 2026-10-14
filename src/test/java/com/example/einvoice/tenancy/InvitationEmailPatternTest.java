package com.example.einvoice.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class InvitationEmailPatternTest {

	static final Pattern EMAIL = Pattern.compile(InvitationController.EMAIL);

	@Test
	void acceptsOrdinaryAddresses() {
		for (String address : List.of("ana@example.test", "ana.perez+ventas@correo.example.com.ec", "a@b.c",
				"x".repeat(64) + "@" + "d".repeat(63) + "." + "e".repeat(63) + "." + "f".repeat(60))) {
			assertThat(EMAIL.matcher(address).matches()).as(address).isTrue();
		}
	}

	@Test
	void rejectsWhatIsNotOneAddress() {
		for (String address : List.of("sin-arroba", "a@sin-punto", "a@b..c", "a@.b.c", "a@b.c.", "a@b.c, d@e.f", "a b@c.d",
				"a@@b.c", "Ana <a@b.c>", "x".repeat(251) + "@b.c")) {
			assertThat(EMAIL.matcher(address).matches()).as(address).isFalse();
		}
	}

	@Test
	void aHostileAddressIsRejectedAtOnce() {
		// La validación corre aunque el tamaño ya esté excedido: no puede costar más cuanto más largo sea.
		for (String hostile : List.of("a@" + "b.".repeat(50_000) + "@", "a@" + ".".repeat(100_000) + "@",
				"a".repeat(100_000))) {
			assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThat(EMAIL.matcher(hostile).matches()).isFalse());
		}
	}

}
