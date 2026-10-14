package com.example.einvoice.identity;

import java.time.InstantSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
 * La API valida los JWT que ella misma emite (AccessTokens) y no mantiene sesión HTTP. Son públicos el
 * health y los endpoints de /api/v1/auth para registrarse, ingresar, renovar y recuperar la contraseña.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ RateLimitProperties.class, AuthProperties.class })
public class SecurityConfig {

	/** Lo que se hace sin sesión: crear la cuenta, entrar, renovar, salir y los enlaces de los correos. */
	static final String[] PUBLIC_AUTH = { "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
			"/api/v1/auth/logout", "/api/v1/auth/email-verification", "/api/v1/auth/password-reset/request",
			"/api/v1/auth/password-reset" };

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

	@Bean
	AccessTokens accessTokens(AuthProperties properties) {
		return new AccessTokens(properties, InstantSource.system());
	}

	@Bean
	JwtDecoder jwtDecoder(AccessTokens tokens) {
		return tokens.decoder();
	}

	@Bean
	AuthAttemptLimiter authAttemptLimiter(StringRedisTemplate redis, AuthProperties properties) {
		return new AuthAttemptLimiter(redis, properties, InstantSource.system());
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
				.requestMatchers(HttpMethod.POST, PUBLIC_AUTH)
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
