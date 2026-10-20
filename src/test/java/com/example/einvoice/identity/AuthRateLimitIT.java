package com.example.einvoice.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.AbstractIntegrationTest;

/**
 * Una dirección IP no puede insistir sin límite en los endpoints públicos; las demás no lo notan. Como
 * en Cloud Run: la web pone la IP del navegador, el frente de Google añade la de la web, y lo que haya a
 * la izquierda lo inventa quien llama.
 */
@TestPropertySource(properties = { "einvoice.auth.public-requests-per-minute=3", "einvoice.auth.trusted-proxy-hops=2" })
class AuthRateLimitIT extends AbstractIntegrationTest {

	/** Cada intento con una primera entrada distinta, inventada: no le sirve para saltarse el límite. */
	ResultActions loginFrom(String ip) throws Exception {
		String forged = "192.0.2." + (1 + (int) (Math.random() * 250));
		return mvc.perform(post("/api/v1/auth/login").header("X-Forwarded-For", forged + ", " + ip + ", 10.0.0.1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"u-" + UUID.randomUUID() + "@example.test\",\"password\":\"una contraseña larga\"}"));
	}

	@Test
	void anAddressThatInsistsWaitsAndTheOthersDoNot() throws Exception {
		String ip = "203.0.113." + (1 + (int) (Math.random() * 200));
		for (int attempt = 0; attempt < 3; attempt++) {
			loginFrom(ip).andExpect(status().isUnauthorized());
		}
		loginFrom(ip).andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
		mvc.perform(post("/api/v1/auth/password-reset/request").header("X-Forwarded-For", ip + ", 10.0.0.2")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"alguien@example.test\"}")).andExpect(status().isTooManyRequests());

		loginFrom("198.51.100.7").andExpect(status().isUnauthorized());
	}

}
