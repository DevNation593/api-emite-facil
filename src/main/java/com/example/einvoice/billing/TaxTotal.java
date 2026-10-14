package com.example.einvoice.billing;

import java.math.BigDecimal;

/** Total de un impuesto para una tarifa: lo que va en totalConImpuestos del comprobante. */
public record TaxTotal(String taxCode, String rateCode, BigDecimal rate, BigDecimal base, BigDecimal amount) {
}
