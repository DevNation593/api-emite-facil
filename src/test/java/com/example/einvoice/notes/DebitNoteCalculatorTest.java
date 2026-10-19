package com.example.einvoice.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.einvoice.billing.InvoiceCalculator;
import com.example.einvoice.billing.InvoiceLineInput;

/** Cálculo de la nota de débito: el IVA se calcula por tarifa, igual que en la factura. */
class DebitNoteCalculatorTest {

	static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	static DebitNoteCalculator.Input input(String reason, String value, String rateCode, String rate) {
		return new DebitNoteCalculator.Input(reason, d(value), rateCode, d(rate));
	}

	@Test
	void groupsTaxByRate() {
		DebitNoteCalculator.Result result = DebitNoteCalculator.calculate(List.of(
				input("Intereses de mora", "10.00", "4", "15"), input("Gastos de cobranza", "5.00", "4", "15"),
				input("Servicio exento", "3.00", "0", "0")));

		assertThat(result.taxes()).hasSize(2);
		NoteTax zero = result.taxes().get(0);
		assertThat(zero.rateCode()).isEqualTo("0");
		assertThat(zero.base()).isEqualByComparingTo("3.00");
		assertThat(zero.amount()).isEqualByComparingTo("0.00");
		NoteTax fifteen = result.taxes().get(1);
		assertThat(fifteen.taxCode()).isEqualTo("2");
		assertThat(fifteen.rateCode()).isEqualTo("4");
		assertThat(fifteen.rate()).isEqualByComparingTo("15");
		assertThat(fifteen.base()).isEqualByComparingTo("15.00");
		assertThat(fifteen.amount()).isEqualByComparingTo("2.25");
		assertThat(result.subtotal()).isEqualTo(d("18.00"));
		assertThat(result.totalTax()).isEqualTo(d("2.25"));
		assertThat(result.total()).isEqualTo(d("20.25"));
		assertThat(result.reasons()).extracting(DebitNoteContent.Reason::taxAmount)
			.containsExactly(d("1.50"), d("0.75"), d("0.00"));
		assertThat(result.reasons().get(0).taxCode()).isEqualTo("2");
	}

	@Test
	void roundsLikeTheInvoice() {
		List<DebitNoteCalculator.Input> inputs = List.of(input("a", "0.33", "4", "15"), input("b", "0.33", "4", "15"),
				input("c", "0.33", "4", "15"));
		List<InvoiceLineInput> lines = inputs.stream()
			.map(i -> new InvoiceLineInput(d("1"), i.value(), BigDecimal.ZERO, i.ivaRateCode(), i.ivaRate()))
			.toList();

		assertThat(DebitNoteCalculator.calculate(inputs).totalTax())
			.isEqualTo(InvoiceCalculator.calculate(lines).totalTax());
		// 0.90 al 15 % son 0.135: se redondea hacia arriba sobre la base agrupada, no por motivo.
		DebitNoteCalculator.Result grouped = DebitNoteCalculator
			.calculate(List.of(input("a", "0.30", "4", "15"), input("b", "0.30", "4", "15"), input("c", "0.30", "4", "15")));
		assertThat(grouped.taxes().get(0).amount()).isEqualTo(d("0.14"));
		assertThat(grouped.totalTax()).isEqualTo(d("0.14"));
	}

	@Test
	void rejectsZeroOrNegativeValue() {
		assertThatThrownBy(() -> DebitNoteCalculator.calculate(List.of(input("a", "0.00", "4", "15"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DebitNoteCalculator.calculate(List.of(input("a", "-1.00", "4", "15"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DebitNoteCalculator.calculate(List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
