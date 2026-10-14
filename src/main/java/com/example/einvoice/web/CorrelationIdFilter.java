package com.example.einvoice.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Asigna a cada petición un identificador de correlación: el que envía el cliente si es seguro de
 * registrar, o uno nuevo. Corre antes que la seguridad para que también los 401 lo lleven.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-ID";

	public static final String ATTRIBUTE = "correlationId";

	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String supplied = request.getHeader(HEADER);
		String correlationId = supplied != null && SAFE.matcher(supplied).matches() ? supplied
				: UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, correlationId);
		response.setHeader(HEADER, correlationId);
		MDC.put(ATTRIBUTE, correlationId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(ATTRIBUTE);
		}
	}

}
