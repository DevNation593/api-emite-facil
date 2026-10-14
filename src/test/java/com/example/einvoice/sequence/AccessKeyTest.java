package com.example.einvoice.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AccessKeyTest {

	@ParameterizedTest
	@CsvSource({
			"071020260117900123450011001002000000123123456781, 0710202601179001234500110010020000001231234567816",
			"071020260117900123450011001002000000006123456781, 0710202601179001234500110010020000000061234567811",
			"071020260117900123450011001002000000008123456781, 0710202601179001234500110010020000000081234567810" })
	void buildsDocumentedVectors(String base48, String expected) {
		assertThat(AccessKey.build(base48)).isEqualTo(expected);
		assertThat(AccessKey.valid(expected)).isTrue();
	}

	@Test
	void detectsAlteredDigit() {
		assertThat(AccessKey.valid("0710202601179001234500110010020000001231234567817")).isFalse();
		assertThat(AccessKey.valid("0710202601179001234500110010020000001241234567816")).isFalse();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "07102026011790012345001100100200000012312345678",
			"07102026011790012345001100100200000012312345678A" })
	void rejectsBaseThatIsNot48Digits(String base48) {
		assertThatThrownBy(() -> AccessKey.build(base48)).isInstanceOf(IllegalArgumentException.class);
		assertThat(AccessKey.valid(base48)).isFalse();
	}

}
