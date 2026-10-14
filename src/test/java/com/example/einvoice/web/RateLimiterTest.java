package com.example.einvoice.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.InstantSource;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RateLimiterTest {

	/** Un puerto de esta máquina en el que nadie escucha. */
	static int closedPort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@Test
	void withRedisUnreachableEveryRequestPassesAndItIsCounted() throws Exception {
		LettuceConnectionFactory factory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration("127.0.0.1", closedPort()),
				LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
		factory.afterPropertiesSet();
		try {
			SimpleMeterRegistry registry = new SimpleMeterRegistry();
			RateLimiter limiter = new RateLimiter(new StringRedisTemplate(factory), new RateLimitProperties(1, 1),
					registry, InstantSource.system());

			// Con el límite en 1, la segunda petición se rechazaría si Redis respondiera.
			for (int i = 0; i < 3; i++) {
				assertThat(limiter.check("usuario", true).allowed()).isTrue();
			}

			assertThat(registry.get("einvoice.ratelimit.unavailable").counter().count()).isEqualTo(3);
		}
		finally {
			factory.destroy();
		}
	}

}
