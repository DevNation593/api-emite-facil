package com.example.einvoice.sri;

import java.text.Normalizer;
import java.util.Locale;

/** Ambientes del SRI, con el código que llevan en el comprobante (ficha técnica, Tabla 4). */
public enum SriEnvironment {

	TEST("1", "PRUEBA"), PRODUCTION("2", "PRODUC");

	private final String code;

	private final String labelPrefix;

	SriEnvironment(String code, String labelPrefix) {
		this.code = code;
		this.labelPrefix = labelPrefix;
	}

	public String code() {
		return code;
	}

	public static SriEnvironment fromCode(String code) {
		for (SriEnvironment environment : values()) {
			if (environment.code.equals(code)) {
				return environment;
			}
		}
		throw new IllegalArgumentException("Ambiente desconocido: " + code);
	}

	/** Si el texto de la etiqueta ambiente de una autorización (PRUEBAS, PRODUCCIÓN) es este ambiente. */
	public boolean matchesLabel(String sriLabel) {
		if (sriLabel == null) {
			return false;
		}
		String plain = Normalizer.normalize(sriLabel, Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.trim()
			.toUpperCase(Locale.ROOT);
		return plain.startsWith(labelPrefix);
	}

}
