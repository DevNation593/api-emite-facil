package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Reglas de cálculo de la ficha técnica v2.34: 9.16 (tabla 21) y 9.17. */
class TaxRoundingTest {

	static InvoiceLineInput line(String quantity, String unitPrice, String discount, String rateCode, String rate) {
		return new InvoiceLineInput(new BigDecimal(quantity), new BigDecimal(unitPrice), new BigDecimal(discount),
				rateCode, new BigDecimal(rate));
	}

	static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	@Test
	void singleLineAtFifteenPercent() {
		InvoiceTotals totals = InvoiceCalculator.calculate(List.of(line("2", "10.00", "0", "4", "15")));
		assertThat(totals.lines()).containsExactly(new LineAmounts(money("20.00"), money("3.00")));
		assertThat(totals.subtotal()).isEqualTo(money("20.00"));
		assertThat(totals.totalDiscount()).isEqualTo(money("0.00"));
		assertThat(totals.taxes())
			.containsExactly(new TaxTotal("2", "4", money("15"), money("20.00"), money("3.00")));
		assertThat(totals.totalTax()).isEqualTo(money("3.00"));
		assertThat(totals.total()).isEqualTo(money("23.00"));
	}

	@Test
	void ivaIsComputedOnTheGroupBaseNotPerLine() {
		InvoiceLineInput cheap = line("1", "0.03", "0", "4", "15");
		InvoiceTotals totals = InvoiceCalculator.calculate(List.of(cheap, cheap, cheap));
		assertThat(totals.lines()).extracting(LineAmounts::taxAmount)
			.containsExactly(money("0.00"), money("0.00"), money("0.00"));
		assertThat(totals.taxes().get(0).base()).isEqualTo(money("0.09"));
		assertThat(totals.taxes().get(0).amount()).isEqualTo(money("0.01"));
		assertThat(totals.total()).isEqualTo(money("0.10"));
	}

	@Test
	void halfCentRoundsUp() {
		InvoiceTotals totals = InvoiceCalculator.calculate(List.of(line("1", "0.005", "0", "0", "0")));
		assertThat(totals.subtotal()).isEqualTo(money("0.01"));
		assertThat(InvoiceCalculator.calculate(List.of(line("1", "0.004999", "0", "0", "0"))).subtotal())
			.isEqualTo(money("0.00"));
	}

	@Test
	void sixDecimalQuantityAndPrice() {
		InvoiceTotals totals = InvoiceCalculator.calculate(List.of(line("1.234567", "9.876543", "0", "2", "12")));
		assertThat(totals.subtotal()).isEqualTo(money("12.19"));
		assertThat(totals.totalTax()).isEqualTo(money("1.46"));
		assertThat(totals.total()).isEqualTo(money("13.65"));
	}

	@Test
	void discountReducesTheBase() {
		InvoiceTotals totals = InvoiceCalculator.calculate(List.of(line("3", "10.00", "5.00", "4", "15")));
		assertThat(totals.subtotal()).isEqualTo(money("25.00"));
		assertThat(totals.totalDiscount()).isEqualTo(money("5.00"));
		assertThat(totals.totalTax()).isEqualTo(money("3.75"));
		assertThat(totals.total()).isEqualTo(money("28.75"));
	}

	@Test
	void discountEqualToLineAmountGivesZero() {
		InvoiceTotals totals = InvoiceCalculator.calculate(List.of(line("1", "10.00", "10.00", "4", "15")));
		assertThat(totals.subtotal()).isEqualTo(money("0.00"));
		assertThat(totals.totalTax()).isEqualTo(money("0.00"));
		assertThat(totals.total()).isEqualTo(money("0.00"));
	}

	@Test
	void mixedRatesAreGroupedAndOrdered() {
		InvoiceTotals totals = InvoiceCalculator.calculate(List.of(line("1", "100.00", "0", "4", "15"),
				line("1", "50.00", "0", "0", "0"), line("2", "10.00", "0", "4", "15"),
				line("1", "20.00", "0", "10", "13")));
		assertThat(totals.taxes()).containsExactly(new TaxTotal("2", "0", money("0"), money("50.00"), money("0.00")),
				new TaxTotal("2", "4", money("15"), money("120.00"), money("18.00")),
				new TaxTotal("2", "10", money("13"), money("20.00"), money("2.60")));
		assertThat(totals.subtotal()).isEqualTo(money("190.00"));
		assertThat(totals.totalTax()).isEqualTo(money("20.60"));
		assertThat(totals.total()).isEqualTo(money("210.60"));
	}

	@Test
	void zeroRatedCodesProduceNoTax() {
		InvoiceTotals totals = InvoiceCalculator.calculate(List.of(line("1", "10.00", "0", "0", "0"),
				line("1", "10.00", "0", "6", "0"), line("1", "10.00", "0", "7", "0")));
		assertThat(totals.taxes()).hasSize(3);
		assertThat(totals.totalTax()).isEqualTo(money("0.00"));
		assertThat(totals.total()).isEqualTo(money("30.00"));
	}

	@Test
	void rejectsInvalidLines() {
		assertThatThrownBy(() -> InvoiceCalculator.calculate(List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InvoiceCalculator.calculate(null)).isInstanceOf(IllegalArgumentException.class);
		for (InvoiceLineInput invalid : List.of(line("0", "10.00", "0", "4", "15"),
				line("-1", "10.00", "0", "4", "15"), line("1", "-0.01", "0", "4", "15"),
				line("1", "10.00", "-0.01", "4", "15"), line("1", "10.00", "10.01", "4", "15"),
				line("1", "10.00", "0", "4", "-1"))) {
			assertThatThrownBy(() -> InvoiceCalculator.calculate(List.of(invalid)))
				.as(invalid.toString())
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void resultsAlwaysHaveTwoDecimals() {
		InvoiceTotals totals = InvoiceCalculator.calculate(List.of(line("1.5", "3.333333", "0.1", "4", "15"),
				line("7", "0.1", "0", "5", "5")));
		assertThat(List.of(totals.subtotal(), totals.totalDiscount(), totals.totalTax(), totals.total()))
			.allSatisfy(value -> assertThat(value.scale()).isEqualTo(2));
		assertThat(totals.lines()).allSatisfy(amounts -> {
			assertThat(amounts.subtotal().scale()).isEqualTo(2);
			assertThat(amounts.taxAmount().scale()).isEqualTo(2);
		});
		assertThat(totals.taxes()).allSatisfy(tax -> {
			assertThat(tax.base().scale()).isEqualTo(2);
			assertThat(tax.amount().scale()).isEqualTo(2);
		});
		assertThat(totals.total()).isEqualTo(totals.subtotal().add(totals.totalTax()));
	}

}
