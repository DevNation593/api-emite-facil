package com.example.einvoice.billing;

import java.math.BigDecimal;

/** Una línea de factura tal como entra al cálculo. ivaRate es el porcentaje (15 para 15 %). */
public record InvoiceLineInput(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, String ivaRateCode,
		BigDecimal ivaRate) {
}
