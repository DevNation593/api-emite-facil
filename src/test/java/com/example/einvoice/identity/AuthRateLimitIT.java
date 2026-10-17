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

/** Una dirección IP no puede insistir sin límite en los endpoints públicos; las demás no lo notan. */
@TestPropertySource(properties = "einvoice.auth.public-requests-per-minute=3")
class AuthRateLimitIT extends AbstractIntegrationTest {

	ResultActions loginFrom(String ip) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").header("X-Forwarded-For", ip + ", 10.0.0.1")
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
		mvc.perform(post("/api/v1/auth/password-reset/request").header("X-Forwarded-For", ip)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"alguien@example.test\"}")).andExpect(status().isTooManyRequests());

		loginFrom("198.51.100.7").andExpect(status().isUnauthorized());
	}

}
