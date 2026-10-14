package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class WebhookSignerTest {

	@Test
	void signsAsTheStandardWebhooksConventionSays() {
		// Vector público de la convención, comprobado aparte con otra implementación de HMAC.
		byte[] key = WebhookSigner.key("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");

		assertThat(WebhookSigner.sign(key, "msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330L,
				"{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8)))
			.isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
	}

	@Test
	void signsWithA32ByteKey() {
		byte[] key = WebhookSigner.key("whsec_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");

		assertThat(WebhookSigner.sign(key, "3f2b0c1e-0d6e-4a57-9c1b-7a3c5d9e8f10", 1790000000L,
				"{\"eventType\":\"document.authorized\"}".getBytes(StandardCharsets.UTF_8)))
			.isEqualTo("v1,NNOva43Ll/DePlhtL06SOqJD6BomR01TtHSXIFlC3TA=");
	}

	@Test
	void theSignatureCoversIdentifierTimeAndBody() {
		byte[] key = WebhookSigner.key(WebhookSigner.newSecret());
		byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
		String signature = WebhookSigner.sign(key, "a", 1L, body);

		assertThat(WebhookSigner.sign(key, "a", 1L, body)).isEqualTo(signature);
		assertThat(WebhookSigner.sign(key, "b", 1L, body)).isNotEqualTo(signature);
		assertThat(WebhookSigner.sign(key, "a", 2L, body)).isNotEqualTo(signature);
		assertThat(WebhookSigner.sign(key, "a", 1L, "{ }".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(signature);
		assertThat(WebhookSigner.sign(WebhookSigner.key(WebhookSigner.newSecret()), "a", 1L, body)).isNotEqualTo(signature);
	}

	@Test
	void everySecretIsNewAndHas32RandomBytes() {
		Set<String> secrets = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			String secret = WebhookSigner.newSecret();
			assertThat(secret).startsWith("whsec_");
			assertThat(Base64.getDecoder().decode(secret.substring(6))).hasSize(32);
			assertThat(WebhookSigner.key(secret)).hasSize(32);
			secrets.add(secret);
		}
		assertThat(secrets).hasSize(100);
	}

	@Test
	void aMalformedSecretIsRefused() {
		assertThatThrownBy(() -> WebhookSigner.key("sin-prefijo")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> WebhookSigner.key("whsec_no es base64")).isInstanceOf(IllegalArgumentException.class);
	}

}
