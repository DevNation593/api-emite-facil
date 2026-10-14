package com.example.einvoice.support;

import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.example.einvoice.identity.JwtValidation;

/**
 * Sustituye el descubrimiento OIDC por la clave pública de {@link TestTokens}. Las reglas de
 * validación son las mismas de producción ({@link JwtValidation}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

	@Bean
	@Primary
	JwtDecoder testJwtDecoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestTokens.publicKey()).build();
		decoder.setJwtValidator(JwtValidation.validator(TestTokens.ISSUER, List.of(TestTokens.AUDIENCE)));
		return decoder;
	}

}
