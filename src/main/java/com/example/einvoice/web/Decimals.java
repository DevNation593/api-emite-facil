package com.example.einvoice.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Importes y cantidades viajan en JSON como cadenas decimales para no perder precisión
 * (docs/11-api-specification.md). Aquí se convierten desde y hacia BigDecimal.
 */
public final class Decimals {

	/** Para anotaciones de validación: cantidad o precio unitario, hasta 12 enteros y 6 decimales. */
	public static final String QUANTITY_OR_PRICE = "[0-9]{1,12}(\\.[0-9]{1,6})?";

	/** Para anotaciones de validación: importe, hasta 12 enteros y 2 decimales. */
	public static final String AMOUNT = "[0-9]{1,12}(\\.[0-9]{1,2})?";

	private static final Pattern PLAIN = Pattern.compile("[0-9]+(\\.[0-9]+)?");

	private Decimals() {
	}

	/** Acepta solo dígitos con punto decimal opcional: sin signo, exponente, comas ni espacios. */
	public static BigDecimal parse(String value, int maxIntegerDigits, int maxFractionDigits) {
		if (value == null || !PLAIN.matcher(value).matches()) {
			throw new IllegalArgumentException("No es un decimal válido: " + value);
		}
		int point = value.indexOf('.');
		int integerDigits = point < 0 ? value.length() : point;
		int fractionDigits = point < 0 ? 0 : value.length() - point - 1;
		if (integerDigits > maxIntegerDigits || fractionDigits > maxFractionDigits) {
			throw new IllegalArgumentException("Demasiados dígitos: " + value);
		}
		return new BigDecimal(value);
	}

	/** Notación plana con exactamente {@code scale} decimales. */
	public static String format(BigDecimal value, int scale) {
		return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
	}

}
