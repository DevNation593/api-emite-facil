package com.example.einvoice.web;

import org.springframework.http.HttpStatus;

/** Prepara un término de búsqueda escrito por el usuario para usarlo en LIKE con ESCAPE '\'. */
public final class LikeTerms {

	public static final int MAX_LENGTH = 100;

	private LikeTerms() {
	}

	/**
	 * Los comodines y la barra invertida que escriba el usuario se buscan como texto literal. Un
	 * término demasiado largo o con caracteres de control se rechaza: la base no acepta el NUL.
	 */
	public static String escape(String term) {
		if (term == null) {
			return "";
		}
		if (term.length() > MAX_LENGTH || !term.matches(TextRules.NO_CONTROL_CHARS)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
					"El término de búsqueda admite hasta " + MAX_LENGTH + " caracteres y ninguno de control");
		}
		return term.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
