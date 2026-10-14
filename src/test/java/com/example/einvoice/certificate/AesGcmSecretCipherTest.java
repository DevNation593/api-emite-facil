package com.example.einvoice.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class AesGcmSecretCipherTest {

	static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

	static final String OTHER_KEY = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

	static final byte[] CONTEXT = "certificado-1".getBytes(StandardCharsets.UTF_8);

	static final byte[] SECRET = "contenido secreto ñ".getBytes(StandardCharsets.UTF_8);

	final AesGcmSecretCipher cipher = new AesGcmSecretCipher(KEY);

	@Test
	void roundTrips() {
		byte[] encrypted = cipher.encrypt(SECRET, CONTEXT);
		assertThat(cipher.decrypt(encrypted, CONTEXT)).isEqualTo(SECRET);
		assertThat(new String(encrypted, StandardCharsets.ISO_8859_1))
			.doesNotContain(new String(SECRET, StandardCharsets.ISO_8859_1));
		assertThat(cipher.decrypt(cipher.encrypt(new byte[0], CONTEXT), CONTEXT)).isEmpty();
	}

	@Test
	void sameInputEncryptsDifferently() {
		assertThat(cipher.encrypt(SECRET, CONTEXT)).isNotEqualTo(cipher.encrypt(SECRET, CONTEXT));
	}

	@Test
	void tamperedCiphertextFails() {
		byte[] encrypted = cipher.encrypt(SECRET, CONTEXT);
		for (int position : new int[] { 0, 5, encrypted.length / 2, encrypted.length - 1 }) {
			byte[] tampered = encrypted.clone();
			tampered[position] ^= 0x01;
			assertThatThrownBy(() -> cipher.decrypt(tampered, CONTEXT)).as("byte %s", position)
				.isInstanceOf(SecretCipherException.class);
		}
		assertThatThrownBy(() -> cipher.decrypt(Arrays.copyOf(encrypted, encrypted.length - 4), CONTEXT))
			.isInstanceOf(SecretCipherException.class);
		assertThatThrownBy(() -> cipher.decrypt(new byte[3], CONTEXT)).isInstanceOf(SecretCipherException.class);
	}

	@Test
	void wrongContextFails() {
		byte[] encrypted = cipher.encrypt(SECRET, CONTEXT);
		assertThatThrownBy(() -> cipher.decrypt(encrypted, "certificado-2".getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(SecretCipherException.class);
	}

	@Test
	void wrongMasterKeyFails() {
		byte[] encrypted = cipher.encrypt(SECRET, CONTEXT);
		assertThatThrownBy(() -> new AesGcmSecretCipher(OTHER_KEY).decrypt(encrypted, CONTEXT))
			.isInstanceOf(SecretCipherException.class);
	}

	@Test
	void rejectsMasterKeyOfWrongLength() {
		String sixteenBytes = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());
		for (String invalid : new String[] { sixteenBytes, "", "no es base64 !!", "   " }) {
			assertThatThrownBy(() -> new AesGcmSecretCipher(invalid)).as(invalid)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("0123456789abcdef");
		}
	}

}
