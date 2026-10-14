package com.example.einvoice.billing;

import java.math.BigDecimal;

/** Importes calculados de una línea: precio total sin impuesto e IVA de la línea. */
public record LineAmounts(BigDecimal subtotal, BigDecimal taxAmount) {
}
