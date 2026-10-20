package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Redondeo de importes común a los comprobantes: dos decimales, mitad hacia arriba (ficha técnica
 * v2.34, 9.17). Lo usan la factura y la nota de débito para calcular igual.
 */
public final class TaxMath {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private TaxMath() {
	}

	/** Porcentaje de una base (la tarifa viene como 15 para 15 %), redondeado a centavos. */
	public static BigDecimal percentage(BigDecimal base, BigDecimal rate) {
		return money(base.multiply(rate).divide(HUNDRED));
	}

	public static BigDecimal money(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP);
	}

}
