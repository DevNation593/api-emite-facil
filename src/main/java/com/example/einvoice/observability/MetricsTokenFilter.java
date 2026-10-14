package com.example.einvoice.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guarda las métricas con una ficha propia, que no es el token de ningún usuario: quien las recoge
 * no pertenece a ninguna empresa. Sin ficha configurada las métricas no existen.
 */
class MetricsTokenFilter extends OncePerRequestFilter {

	private static final int MIN_LENGTH = 24;

	private final byte[] expected;

	MetricsTokenFilter(String token) {
		String value = token == null ? "" : token.strip();
		if (!value.isEmpty() && value.length() < MIN_LENGTH) {
			throw new IllegalStateException(
					"EINVOICE_METRICS_TOKEN debe tener al menos " + MIN_LENGTH + " caracteres aleatorios");
		}
		this.expected = value.isEmpty() ? null : ("Bearer " + value).getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (expected == null) {
			// Estado directo, sin sendError: el despacho de errores del contenedor pasaría por la
			// seguridad de la API y el 404 saldría convertido en 401.
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
		// Comparación en tiempo constante: el tiempo de respuesta no revela cuánto de la ficha se acertó.
		if (!MessageDigest.isEqual(expected, given)) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		chain.doFilter(request, response);
	}

}
