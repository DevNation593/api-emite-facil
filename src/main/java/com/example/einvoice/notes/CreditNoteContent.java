package com.example.einvoice.notes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Contenido congelado de una nota de crédito: el motivo, las líneas devueltas y los impuestos
 * agrupados. Lo demás (emisor, receptor, totales, factura modificada) va en el sobre del documento.
 * invoiceLineNo y productId son nulos cuando la línea no viene de una factura de la plataforma.
 */
public record CreditNoteContent(String reason, List<Line> lines, List<NoteTax> taxes) {

	public CreditNoteContent {
		lines = List.copyOf(lines);
		taxes = List.copyOf(taxes);
	}

	public record Line(Integer invoiceLineNo, UUID productId, String mainCode, String description,
			BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal subtotal,
			String taxCode, String taxRateCode, BigDecimal taxRate, BigDecimal taxAmount) {
	}

}
