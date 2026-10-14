package com.example.einvoice.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** Las métricas tienen su propia puerta, antes que la de la API: no se entra con un token de usuario. */
@Configuration(proxyBeanMethods = false)
class MetricsSecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain metricsSecurity(HttpSecurity http, @Value("${einvoice.metrics.token:}") String token)
			throws Exception {
		// Por el punto de operación, no por su ruta: si alguien mueve la ruta base, la puerta va con él.
		http.securityMatcher(EndpointRequest.to("prometheus"))
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
			.addFilterBefore(new MetricsTokenFilter(token), AuthorizationFilter.class);
		return http.build();
	}

}
