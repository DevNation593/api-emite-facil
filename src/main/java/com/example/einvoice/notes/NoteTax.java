package com.example.einvoice.notes;

import java.math.BigDecimal;

/** Impuesto agrupado de una nota: código, tarifa, base imponible y valor. */
public record NoteTax(String taxCode, String rateCode, BigDecimal rate, BigDecimal base, BigDecimal amount) {
}
