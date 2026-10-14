package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.einvoice.catalog.CatalogRepository;

/**
 * Cálculo de una factura según la ficha técnica v2.34. Los importes llevan dos decimales (9.17); el
 * IVA de cada tarifa se calcula sobre la suma de las bases de esa tarifa, no sumando el IVA de cada
 * línea (9.16, tabla 21), así que ambos pueden diferir en centavos.
 */
public final class InvoiceCalculator {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private InvoiceCalculator() {
	}

	public static InvoiceTotals calculate(List<InvoiceLineInput> lines) {
		if (lines == null || lines.isEmpty()) {
			throw new IllegalArgumentException("La factura necesita al menos una línea");
		}
		List<LineAmounts> amounts = new ArrayList<>(lines.size());
		Map<String, BigDecimal> rateByCode = new LinkedHashMap<>();
		Map<String, BigDecimal> baseByCode = new LinkedHashMap<>();
		BigDecimal subtotal = money(BigDecimal.ZERO);
		BigDecimal totalDiscount = money(BigDecimal.ZERO);
		for (InvoiceLineInput line : lines) {
			BigDecimal gross = validate(line);
			BigDecimal lineSubtotal = money(gross.subtract(line.discount()));
			amounts.add(new LineAmounts(lineSubtotal, percentage(lineSubtotal, line.ivaRate())));
			subtotal = subtotal.add(lineSubtotal);
			totalDiscount = totalDiscount.add(money(line.discount()));
			rateByCode.putIfAbsent(line.ivaRateCode(), line.ivaRate());
			baseByCode.merge(line.ivaRateCode(), lineSubtotal, BigDecimal::add);
		}
		List<TaxTotal> taxes = baseByCode.entrySet()
			.stream()
			.map(entry -> new TaxTotal(CatalogRepository.IVA_TAX_CODE, entry.getKey(), rateByCode.get(entry.getKey()),
					entry.getValue(), percentage(entry.getValue(), rateByCode.get(entry.getKey()))))
			.sorted(Comparator.comparingInt((TaxTotal tax) -> Integer.parseInt(tax.rateCode())))
			.toList();
		BigDecimal totalTax = taxes.stream().map(TaxTotal::amount).reduce(money(BigDecimal.ZERO), BigDecimal::add);
		return new InvoiceTotals(List.copyOf(amounts), subtotal, totalDiscount, taxes, totalTax,
				subtotal.add(totalTax));
	}

	/** Devuelve cantidad × precio, tras comprobar que la línea es calculable. */
	private static BigDecimal validate(InvoiceLineInput line) {
		if (line.quantity() == null || line.quantity().signum() <= 0) {
			throw new IllegalArgumentException("La cantidad debe ser mayor que cero");
		}
		if (line.unitPrice() == null || line.unitPrice().signum() < 0) {
			throw new IllegalArgumentException("El precio unitario no puede ser negativo");
		}
		if (line.discount() == null || line.discount().signum() < 0) {
			throw new IllegalArgumentException("El descuento no puede ser negativo");
		}
		if (line.ivaRate() == null || line.ivaRate().signum() < 0 || line.ivaRateCode() == null) {
			throw new IllegalArgumentException("La tarifa de IVA no es válida");
		}
		BigDecimal gross = line.quantity().multiply(line.unitPrice());
		if (line.discount().compareTo(gross) > 0) {
			throw new IllegalArgumentException("El descuento no puede superar el importe de la línea");
		}
		return gross;
	}

	private static BigDecimal percentage(BigDecimal base, BigDecimal rate) {
		return money(base.multiply(rate).divide(HUNDRED));
	}

	private static BigDecimal money(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP);
	}

}
