package com.example.einvoice.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.example.einvoice.web.ApiException;

class PasswordsTest {

	final Passwords passwords = new Passwords();

	@Test
	void storesArgon2idAndChecksIt() {
		String hash = passwords.hash("una contraseña larga");
		assertThat(hash).startsWith("$argon2id$").isNotEqualTo(passwords.hash("una contraseña larga"));
		assertThat(passwords.matches("una contraseña larga", hash)).isTrue();
		assertThat(passwords.matches("otra contraseña larga", hash)).isFalse();
	}

	@Test
	void anAccountWithoutPasswordNeverMatches() {
		assertThat(passwords.matches("cualquier cosa", null)).isFalse();
		assertThat(passwords.matches(null, null)).isFalse();
	}

	@Test
	void onlyLengthAndNotBeingTheEmailMatter() {
		assertThatCode(() -> passwords.requireAcceptable("password", "solo minusculas", "a@b.ec")).doesNotThrowAnyException();
		assertThatCode(() -> passwords.requireAcceptable("password", "ñ".repeat(12), "a@b.ec")).doesNotThrowAnyException();
		for (String weak : new String[] { null, "corta", "x".repeat(11), "x".repeat(129), " Usuario@Example.EC " }) {
			assertThatThrownBy(() -> passwords.requireAcceptable("password", weak, "usuario@example.ec"))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.errorCode()).isEqualTo("WEAK_PASSWORD");
					assertThat(ex.errors()).extracting(ApiException.FieldError::field).containsExactly("password");
				});
		}
	}

}
