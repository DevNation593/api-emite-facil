package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.util.List;

/** Resultado del cálculo de una factura; lines sigue el orden de las líneas de entrada. */
public record InvoiceTotals(List<LineAmounts> lines, BigDecimal subtotal, BigDecimal totalDiscount,
		List<TaxTotal> taxes, BigDecimal totalTax, BigDecimal total) {
}
