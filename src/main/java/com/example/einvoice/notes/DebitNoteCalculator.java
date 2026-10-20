package com.example.einvoice.notes;

import static com.example.einvoice.billing.TaxMath.money;
import static com.example.einvoice.billing.TaxMath.percentage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.einvoice.catalog.CatalogRepository;

/**
 * Cálculo de una nota de débito. Redondea como la factura (dos decimales, mitad hacia arriba): el IVA
 * de cada tarifa se calcula sobre la suma de los valores de esa tarifa, no sumando el de cada motivo.
 */
public final class DebitNoteCalculator {

	private DebitNoteCalculator() {
	}

	/** Un motivo tal como entra al cálculo. ivaRate es el porcentaje (15 para 15 %). */
	public record Input(String reason, BigDecimal value, String ivaRateCode, BigDecimal ivaRate) {
	}

	public record Result(List<DebitNoteContent.Reason> reasons, List<NoteTax> taxes, BigDecimal subtotal,
			BigDecimal totalTax, BigDecimal total) {
	}

	public static Result calculate(List<Input> inputs) {
		if (inputs == null || inputs.isEmpty()) {
			throw new IllegalArgumentException("La nota de débito necesita al menos un motivo");
		}
		List<DebitNoteContent.Reason> reasons = new ArrayList<>(inputs.size());
		Map<String, BigDecimal> rateByCode = new LinkedHashMap<>();
		Map<String, BigDecimal> baseByCode = new LinkedHashMap<>();
		BigDecimal subtotal = money(BigDecimal.ZERO);
		for (Input input : inputs) {
			validate(input);
			BigDecimal value = money(input.value());
			reasons.add(new DebitNoteContent.Reason(input.reason(), value, CatalogRepository.IVA_TAX_CODE,
					input.ivaRateCode(), input.ivaRate(), percentage(value, input.ivaRate())));
			subtotal = subtotal.add(value);
			rateByCode.putIfAbsent(input.ivaRateCode(), input.ivaRate());
			baseByCode.merge(input.ivaRateCode(), value, BigDecimal::add);
		}
		List<NoteTax> taxes = baseByCode.entrySet()
			.stream()
			.map(entry -> new NoteTax(CatalogRepository.IVA_TAX_CODE, entry.getKey(), rateByCode.get(entry.getKey()),
					entry.getValue(), percentage(entry.getValue(), rateByCode.get(entry.getKey()))))
			.sorted(Comparator.comparingInt((NoteTax tax) -> Integer.parseInt(tax.rateCode())))
			.toList();
		BigDecimal totalTax = taxes.stream().map(NoteTax::amount).reduce(money(BigDecimal.ZERO), BigDecimal::add);
		return new Result(List.copyOf(reasons), taxes, subtotal, totalTax, subtotal.add(totalTax));
	}

	private static void validate(Input input) {
		if (input.value() == null || input.value().signum() <= 0) {
			throw new IllegalArgumentException("El valor del motivo debe ser mayor que cero");
		}
		if (input.ivaRate() == null || input.ivaRate().signum() < 0 || input.ivaRateCode() == null) {
			throw new IllegalArgumentException("La tarifa de IVA no es válida");
		}
	}

}
