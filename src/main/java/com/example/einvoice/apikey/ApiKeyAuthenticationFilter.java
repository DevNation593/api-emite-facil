package com.example.einvoice.apikey;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.example.einvoice.web.ApiException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Autentica la petición que trae la cabecera X-Api-Key. Sin la cabecera no hace nada y la petición
 * sigue hacia el JWT. Con ella, aunque venga vacía, la key tiene que servir: una cabecera presente no
 * se trata nunca como si faltara. El valor de la cabecera no se registra en ningún sitio.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Api-Key";

	private final ApiKeyService keys;

	private final HandlerExceptionResolver resolver;

	private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

	private final SecurityContextRepository repository = new RequestAttributeSecurityContextRepository();

	public ApiKeyAuthenticationFilter(ApiKeyService keys, HandlerExceptionResolver resolver) {
		this.keys = keys;
		this.resolver = resolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String value = request.getHeader(HEADER);
		if (value == null) {
			chain.doFilter(request, response);
			return;
		}
		// Dos credenciales a la vez: no se elige una por el cliente.
		if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
			reject(request, response, new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIALS_AMBIGUOUS",
					"Envíe X-Api-Key o Authorization, no las dos"));
			return;
		}
		Optional<ApiKeyAuthentication> authentication = keys.authenticate(value);
		if (authentication.isEmpty()) {
			// La misma respuesta que un token inválido, sea cual sea el motivo.
			reject(request, response,
					new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere un token de acceso válido"));
			return;
		}
		SecurityContext context = contexts.createEmptyContext();
		context.setAuthentication(authentication.get());
		contexts.setContext(context);
		repository.saveContext(context, request, response);
		keys.touch(authentication.get().apiKeyId());
		chain.doFilter(request, response);
	}

	private void reject(HttpServletRequest request, HttpServletResponse response, ApiException error) {
		contexts.clearContext();
		resolver.resolveException(request, response, null, error);
	}

}
