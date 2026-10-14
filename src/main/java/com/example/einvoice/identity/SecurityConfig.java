package com.example.einvoice.identity;

import java.time.InstantSource;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.example.einvoice.web.ApiException;
import com.example.einvoice.web.RateLimitFilter;
import com.example.einvoice.web.RateLimitProperties;
import com.example.einvoice.web.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * La API es un resource server OAuth2: valida JWT del emisor OIDC configurado y no mantiene sesión.
 * Solo el health es público.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfig {

	/** Reloj del límite de peticiones; las pruebas ponen el suyo. */
	@Bean
	@ConditionalOnMissingBean
	InstantSource rateLimitClock() {
		return InstantSource.system();
	}

	@Bean
	RateLimiter rateLimiter(StringRedisTemplate redis, RateLimitProperties properties, MeterRegistry registry,
			InstantSource clock) {
		return new RateLimiter(redis, properties, registry, clock);
	}

	/**
	 * Decodificador de producción. La configuración del proveedor se descarga en el primer token,
	 * no al arrancar, para que una caída del proveedor no impida levantar la aplicación.
	 */
	@Bean
	JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
			@Value("${spring.security.oauth2.resourceserver.jwt.audiences}") List<String> audiences) {
		return new SupplierJwtDecoder(() -> {
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
			decoder.setJwtValidator(JwtValidation.validator(issuer, audiences));
			return decoder;
		});
	}

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver, RateLimiter limiter)
			throws Exception {
		// Los rechazos de seguridad pasan por ApiExceptionHandler para salir como problem+json.
		AuthenticationEntryPoint unauthenticated = (request, response, ex) -> resolver.resolveException(request,
				response, null,
				new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere un token de acceso válido"));
		AccessDeniedHandler forbidden = (request, response, ex) -> resolver.resolveException(request, response, null,
				new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acceso denegado"));
		http.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests.requestMatchers(EndpointRequest.to("health"))
				.permitAll()
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(unauthenticated)
				.accessDeniedHandler(forbidden))
			.exceptionHandling(
					handling -> handling.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
			// Con el usuario ya identificado, antes de llegar a ningún controlador.
			.addFilterAfter(new RateLimitFilter(limiter, resolver), BearerTokenAuthenticationFilter.class);
		return http.build();
	}

}
