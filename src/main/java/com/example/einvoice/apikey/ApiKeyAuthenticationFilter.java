package com.example.einvoice.apikey;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.TransactionException;
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
 *
 * <p>
 * Si la base no responde al comprobar la key, la respuesta es 503: nunca 401, que el sistema externo
 * leería como una key inválida y podría dejar de llamar. Anotar el último uso no es imprescindible: si
 * falla, la petición sigue.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Api-Key";

	private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

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
		Optional<ApiKeyAuthentication> authentication;
		try {
			authentication = keys.authenticate(value);
		}
		catch (DataAccessException | TransactionException ex) {
			// Sin conexión, el fallo llega al abrir la transacción (TransactionException). La consulta lleva solo el publicId como parámetro: el registro no contiene el secreto.
			log.error("No se pudo comprobar una API key: falló la base", ex);
			reject(request, response, new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_ERROR",
					"No se pudo comprobar la API key; vuelva a intentar en un momento"));
			return;
		}
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
		touch(authentication.get().apiKeyId());
		chain.doFilter(request, response);
	}

	/** El último uso es informativo: un fallo al anotarlo no deja sin servicio una key válida. */
	private void touch(UUID apiKeyId) {
		try {
			keys.touch(apiKeyId);
		}
		catch (DataAccessException | TransactionException ex) {
			log.warn("No se pudo anotar el último uso de la API key {}", apiKeyId, ex);
		}
	}

	private void reject(HttpServletRequest request, HttpServletResponse response, ApiException error) {
		contexts.clearContext();
		resolver.resolveException(request, response, null, error);
	}

}
