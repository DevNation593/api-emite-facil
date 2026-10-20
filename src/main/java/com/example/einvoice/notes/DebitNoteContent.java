package com.example.einvoice.notes;

import java.math.BigDecimal;
import java.util.List;

/**
 * Contenido congelado de una nota de débito: los motivos con su valor e IVA, los impuestos agrupados
 * por tarifa y el pago. Lo demás (emisor, receptor, totales, factura modificada) va en el sobre.
 */
public record DebitNoteContent(List<Reason> reasons, List<NoteTax> taxes, List<Payment> payments) {

	public DebitNoteContent {
		reasons = List.copyOf(reasons);
		taxes = List.copyOf(taxes);
		payments = List.copyOf(payments);
	}

	public record Reason(String reason, BigDecimal value, String taxCode, String taxRateCode, BigDecimal taxRate,
			BigDecimal taxAmount) {
	}

	public record Payment(String methodCode, BigDecimal amount) {
	}

}
