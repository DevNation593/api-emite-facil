package com.example.einvoice.web;

import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import org.testcontainers.containers.GenericContainer;

import com.example.einvoice.support.AbstractIntegrationTest;

/** Un usuario que pide demasiado en un minuto recibe 429; los demás no lo notan. */
@TestPropertySource(properties = { "einvoice.rate-limit.requests-per-minute=8",
		"einvoice.rate-limit.writes-per-minute=3" })
@Import(RateLimitIT.ClockConfig.class)
class RateLimitIT extends AbstractIntegrationTest {

	static class TestClock implements InstantSource {

		// A mitad de un minuto, para que avanzar unos segundos no cambie de ventana.
		volatile Instant now = Instant.parse("2026-10-14T15:00:30Z");

		@Override
		public Instant instant() {
			return now;
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfig {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}

	}

	@Autowired
	TestClock clock;

	@Autowired
	@Qualifier("redis")
	GenericContainer<?> redis;

	ResultActions newTenant(String token) throws Exception {
		return mvc.perform(json(post("/api/v1/tenants"), token, "{\"name\":\"Comercial Andina\"}"));
	}

	/** Cada prueba usa su propio minuto, a mitad de él: las cuentas de una no llegan a la siguiente. */
	void anotherMinute() {
		clock.now = clock.now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(5)).plusSeconds(30);
	}

	@Test
	void theWriteOverTheLimitIsRefusedWithAWellFormedAnswer() throws Exception {
		anotherMinute();
		String user = newUserToken();
		for (int i = 0; i < 3; i++) {
			newTenant(user).andExpect(status().isCreated());
		}

		MvcResult refused = newTenant(user).andExpect(status().isTooManyRequests())
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
			.andExpect(jsonPath("$.correlationId", notNullValue()))
			.andReturn();

		// Cuántos segundos faltan para la siguiente ventana.
		assertThat(Integer.parseInt(refused.getResponse().getHeader("Retry-After"))).isEqualTo(30);
		// La petición rechazada no llegó a crear nada.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM memberships WHERE user_id = ?::uuid", Integer.class,
				subjectOf(user)))
			.isEqualTo(3);
	}

	@Test
	void anotherUserIsNotAffected() throws Exception {
		anotherMinute();
		String heavy = newUserToken();
		for (int i = 0; i < 4; i++) {
			newTenant(heavy);
		}
		newTenant(heavy).andExpect(status().isTooManyRequests());

		newTenant(newUserToken()).andExpect(status().isCreated());
	}

	@Test
	void readsAreCountedApartAndHaveTheirOwnLimit() throws Exception {
		anotherMinute();
		String user = newUserToken();
		for (int i = 0; i < 3; i++) {
			newTenant(user).andExpect(status().isCreated());
		}
		newTenant(user).andExpect(status().isTooManyRequests());

		// Agotadas las escrituras, todavía puede leer: lleva 3 peticiones de 8 (la rechazada no cuenta).
		for (int i = 0; i < 5; i++) {
			mvc.perform(as(get("/api/v1/tenants"), user)).andExpect(status().isOk());
		}
		mvc.perform(as(get("/api/v1/tenants"), user)).andExpect(status().isTooManyRequests());
	}

	@Test
	void theNextMinuteStartsClean() throws Exception {
		anotherMinute();
		String user = newUserToken();
		for (int i = 0; i < 3; i++) {
			newTenant(user).andExpect(status().isCreated());
		}
		newTenant(user).andExpect(status().isTooManyRequests());

		clock.now = clock.now.plusSeconds(30);

		newTenant(user).andExpect(status().isCreated());
	}

	@Test
	void aRedisThatStopsAnsweringDoesNotHoldRequests() throws Exception {
		anotherMinute();
		String user = newUserToken();
		newTenant(user).andExpect(status().isCreated());

		// Redis sigue ahí pero no contesta: el caso de una red cortada o un servidor colgado.
		redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
		try {
			long started = System.nanoTime();
			newTenant(user).andExpect(status().isCreated());
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

			// Tras el primer fallo no se vuelve a esperar a Redis en cada petición.
			started = System.nanoTime();
			for (int i = 0; i < 5; i++) {
				newTenant(newUserToken()).andExpect(status().isCreated());
			}
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
		}
		finally {
			redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
		}

		// Cuando Redis vuelve, pasado el descanso, el límite se aplica otra vez.
		clock.now = clock.now.plusSeconds(15);
		String next = newUserToken();
		for (int i = 0; i < 3; i++) {
			newTenant(next).andExpect(status().isCreated());
		}
		newTenant(next).andExpect(status().isTooManyRequests());
	}

	@Test
	void requestsWithoutAUserAreNotCounted() throws Exception {
		anotherMinute();
		for (int i = 0; i < 12; i++) {
			mvc.perform(get("/actuator/health")).andExpect(status().isOk());
			mvc.perform(get("/api/v1/tenants")).andExpect(status().isUnauthorized());
		}
	}

	private static String subjectOf(String token) throws Exception {
		String payload = token.substring(token.indexOf('.') + 1, token.lastIndexOf('.'));
		String json = new String(java.util.Base64.getUrlDecoder().decode(payload), java.nio.charset.StandardCharsets.UTF_8);
		return com.jayway.jsonpath.JsonPath.read(json, "$.sub");
	}

}
