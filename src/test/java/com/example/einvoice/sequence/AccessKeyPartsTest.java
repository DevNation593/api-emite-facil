package com.example.einvoice.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccessKeyPartsTest {

	static AccessKeyParts parts(int sequence) {
		return new AccessKeyParts(LocalDate.of(2026, 10, 7), "01", "1790012345001", "1", "001", "002", sequence,
				"12345678", "1");
	}

	@Test
	void buildsBaseAndKeyKeepingLeadingZeros() {
		assertThat(parts(123).toBase48()).isEqualTo("071020260117900123450011001002000000123123456781");
		assertThat(parts(123).toAccessKey()).isEqualTo("0710202601179001234500110010020000001231234567816");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, 1_000_000_000 })
	void rejectsSequenceOutOfRange(int sequence) {
		assertThatThrownBy(() -> parts(sequence)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsMalformedComponents() {
		LocalDate date = LocalDate.of(2026, 10, 7);
		assertThatThrownBy(
				() -> new AccessKeyParts(null, "01", "1790012345001", "1", "001", "002", 1, "12345678", "1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new AccessKeyParts(date, "1", "1790012345001", "1", "001", "002", 1, "12345678", "1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new AccessKeyParts(date, "01", "179001234500", "1", "001", "002", 1, "12345678", "1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new AccessKeyParts(date, "01", "1790012345001", "3", "001", "002", 1, "12345678", "1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new AccessKeyParts(date, "01", "1790012345001", "1", "1", "002", 1, "12345678", "1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new AccessKeyParts(date, "01", "1790012345001", "1", "001", "002", 1, "1234567", "1"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fiscalDateIsEcuadorLocalDate() {
		assertThat(FiscalClock.dateOf(Instant.parse("2026-10-08T03:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 7));
		assertThat(FiscalClock.dateOf(Instant.parse("2026-10-08T05:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 8));
	}

	@RepeatedTest(20)
	void numericCodeHasEightDigits() {
		assertThat(new NumericCodeGenerator().next()).matches("[0-9]{8}");
	}

}
