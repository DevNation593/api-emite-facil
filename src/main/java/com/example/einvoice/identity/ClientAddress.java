package com.example.einvoice.identity;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * La IP de quien llama, para el límite de intentos por IP de /api/v1/auth.
 *
 * <p>Cada proxy de confianza añade su entrada al final de X-Forwarded-For (el frente de Google lo hace
 * así en Cloud Run); lo que hay a la izquierda lo escribe quien llama y no vale. Con {@code hops}
 * proxies de confianza, la IP es la entrada que queda en esa posición contando desde la derecha: en
 * Cloud Run la web pone la IP del navegador y el frente de Google añade la de la web, así que son 2.
 * Un proxy que reemplaza la cabecera, o la web llamando directo, deja una sola entrada: 1.
 *
 * <p>Si hay menos entradas que proxies, todas las puso alguno de confianza y vale la primera.
 */
final class ClientAddress {

	static final String HEADER = "X-Forwarded-For";

	/** Más larga que cualquier IPv6 escrita; lo que la supere no es una dirección. */
	private static final int MAX_LENGTH = 64;

	private ClientAddress() {
	}

	/** La IP según X-Forwarded-For; sin ella o si no se puede leer, la del que llama. */
	static String of(HttpServletRequest request, int hops) {
		List<String> headers = new ArrayList<>();
		request.getHeaders(HEADER).asIterator().forEachRemaining(headers::add);
		String forwarded = fromForwardedFor(headers, hops);
		return forwarded != null ? forwarded : request.getRemoteAddr();
	}

	/**
	 * La entrada en la posición {@code hops} contando desde la derecha, o null si no hay ninguna utilizable.
	 * Varias cabeceras X-Forwarded-For cuentan como una sola lista, en su orden.
	 */
	static String fromForwardedFor(List<String> headers, int hops) {
		List<String> entries = new ArrayList<>();
		for (String header : headers) {
			if (header != null && !header.isBlank()) {
				for (String entry : header.split(",", -1)) {
					entries.add(entry.strip());
				}
			}
		}
		if (entries.isEmpty()) {
			return null;
		}
		String entry = entries.get(Math.max(0, entries.size() - hops));
		return entry.isEmpty() || entry.length() > MAX_LENGTH ? null : entry;
	}

}
