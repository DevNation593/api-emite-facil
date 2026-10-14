package com.example.einvoice.web;

/** Reglas comunes de validación de texto libre recibido por la API. */
public final class TextRules {

	/** Sin caracteres de control: PostgreSQL rechaza el NUL y ninguno es válido en un comprobante. */
	public static final String NO_CONTROL_CHARS = "[^\\p{Cntrl}]*";

	private TextRules() {
	}

}
