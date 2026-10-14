package com.example.einvoice.web;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Aplica el límite de peticiones al usuario ya autenticado. Lo que llega sin usuario no se cuenta
 * aquí: frenar ese tráfico es tarea del proxy de entrada.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

	private final RateLimiter limiter;

	private final HandlerExceptionResolver resolver;

	public RateLimitFilter(RateLimiter limiter, HandlerExceptionResolver resolver) {
		this.limiter = limiter;
		this.resolver = resolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			chain.doFilter(request, response);
			return;
		}
		RateLimiter.Decision decision = limiter.check(authentication.getName(), !READS.contains(request.getMethod()));
		if (decision.allowed()) {
			chain.doFilter(request, response);
			return;
		}
		response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
		// Sale como problem+json, igual que el resto de errores de la API.
		resolver.resolveException(request, response, null, new ApiException(HttpStatus.TOO_MANY_REQUESTS,
				"RATE_LIMITED", "Demasiadas peticiones en poco tiempo; espere unos segundos"));
	}

}
