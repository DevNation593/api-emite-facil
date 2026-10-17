package com.example.einvoice.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ApiKeyCredentialTest {

	private static final String SHAPE = "^efk_[A-Za-z0-9]{12}_[A-Za-z0-9_-]{43}$";

	/** El secreto es lo que sigue al publicId: posición fija, porque el secreto puede contener guiones bajos. */
	private static String secretOf(String value) {
		return value.substring("efk_".length() + 12 + 1);
	}

	@Test
	void generatedValueHasTheDocumentedShape() {
		ApiKeyCredential.Generated g = ApiKeyCredential.generate();

		assertThat(g.value()).matches(SHAPE);
		assertThat(g.publicId()).isEqualTo(g.value().substring(4, 16));
		assertThat(g.secretHash()).isEqualTo(ApiKeyCredential.hash(secretOf(g.value())));
		assertThat(g.secretHash()).matches("^[0-9a-f]{64}$");
	}

	@Test
	void twoGeneratedKeysDiffer() {
		Set<String> ids = new HashSet<>();
		Set<String> values = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			ApiKeyCredential.Generated g = ApiKeyCredential.generate();
			ids.add(g.publicId());
			values.add(g.value());
		}

		assertThat(ids).hasSize(1000);
		assertThat(values).hasSize(1000);
	}

	@Test
	void parseRoundTrips() {
		ApiKeyCredential.Generated g = ApiKeyCredential.generate();

		assertThat(ApiKeyCredential.parse(g.value()))
			.contains(new ApiKeyCredential.Presented(g.publicId(), secretOf(g.value())));
	}

	@Test
	void parseKeepsSecretsThatContainUnderscores() {
		String value = "efk_abcdefABCDEF_" + "_".repeat(43);

		assertThat(ApiKeyCredential.parse(value))
			.contains(new ApiKeyCredential.Presented("abcdefABCDEF", "_".repeat(43)));
	}

	@Test
	void parseTrimsSurroundingWhitespace() {
		ApiKeyCredential.Generated g = ApiKeyCredential.generate();

		assertThat(ApiKeyCredential.parse("  " + g.value() + "\n")).isPresent();
	}

	@Test
	void parseRejectsMalformed() {
		String secret = "a".repeat(43);

		assertThat(ApiKeyCredential.parse(null)).isEmpty();
		assertThat(ApiKeyCredential.parse("")).isEmpty();
		assertThat(ApiKeyCredential.parse("   ")).isEmpty();
		assertThat(ApiKeyCredential.parse("efk_")).isEmpty();
		assertThat(ApiKeyCredential.parse("efx_abcdefABCDEF_" + secret)).isEmpty();
		assertThat(ApiKeyCredential.parse("efk_abcdefABCDE_" + secret)).isEmpty();
		assertThat(ApiKeyCredential.parse("efk_abcdefABCDEF_" + "a".repeat(42))).isEmpty();
		assertThat(ApiKeyCredential.parse("efk_abcdefABCDEF_" + "a".repeat(42) + "+")).isEmpty();
		assertThat(ApiKeyCredential.parse("efk_abcdefABCDEF_" + "a".repeat(42) + "/")).isEmpty();
		assertThat(ApiKeyCredential.parse("efk_abcdefABCDEF_" + "a".repeat(20) + " " + "a".repeat(22))).isEmpty();
	}

	@Test
	void matchesIsTrueOnlyForTheSameSecret() {
		String s = "a".repeat(43);

		assertThat(ApiKeyCredential.matches(s, ApiKeyCredential.hash(s))).isTrue();
		assertThat(ApiKeyCredential.matches(s + "x", ApiKeyCredential.hash(s))).isFalse();
	}

}
