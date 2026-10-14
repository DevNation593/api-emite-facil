package com.example.einvoice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DecimalsTest {

	@Test
	void parsesPlainDecimals() {
		assertThat(Decimals.parse("0", 12, 6)).isEqualByComparingTo("0");
		assertThat(Decimals.parse("10.50", 12, 6)).isEqualTo(new BigDecimal("10.50"));
		assertThat(Decimals.parse("0.000001", 12, 6)).isEqualTo(new BigDecimal("0.000001"));
		assertThat(Decimals.parse("999999999999.999999", 12, 6)).isEqualTo(new BigDecimal("999999999999.999999"));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "-1", "+1", "1e3", "1E3", "1,5", " 1", "1 ", "1.", ".5", "1.2.3", "abc", "0x10",
			"1234567890123", "1.1234567", "１２" })
	void rejectsEverythingElse(String value) {
		assertThatThrownBy(() -> Decimals.parse(value, 12, 6)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void limitsAreParameters() {
		assertThat(Decimals.parse("12.34", 2, 2)).isEqualTo(new BigDecimal("12.34"));
		assertThatThrownBy(() -> Decimals.parse("123.4", 2, 2)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Decimals.parse("12.345", 2, 2)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void formatsWithFixedScaleAndNoExponent() {
		assertThat(Decimals.format(new BigDecimal("2.5"), 2)).isEqualTo("2.50");
		assertThat(Decimals.format(new BigDecimal("1E+3"), 2)).isEqualTo("1000.00");
		assertThat(Decimals.format(new BigDecimal("10.5"), 6)).isEqualTo("10.500000");
		assertThat(Decimals.format(new BigDecimal("0.005"), 2)).isEqualTo("0.01");
		assertThat(Decimals.format(BigDecimal.ZERO, 2)).isEqualTo("0.00");
	}

}
