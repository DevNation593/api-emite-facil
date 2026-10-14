package com.example.einvoice.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Límite de peticiones por usuario, en ventanas de un minuto contadas en Redis (así vale entre
 * instancias). Las escrituras tienen además su propio tope, más bajo. Si Redis no responde, la
 * petición pasa: un fallo del contador no debe dejar a nadie sin facturar. Y tras un fallo se le
 * deja descansar unos segundos, para que un Redis colgado cueste una espera corta y no una por
 * petición.
 */
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private static final long WINDOW_SECONDS = 60;

	/** El contador sobrevive a su ventana lo justo para no perderse por un desfase de relojes. */
	private static final Duration KEY_LIFETIME = Duration.ofSeconds(2 * WINDOW_SECONDS);

	/** Tras un fallo de Redis no se le vuelve a preguntar durante este tiempo. */
	private static final Duration REST_AFTER_FAILURE = Duration.ofSeconds(10);

	public record Decision(boolean allowed, long retryAfterSeconds) {
	}

	private final StringRedisTemplate redis;

	private final RateLimitProperties properties;

	private final Counter unavailable;

	private final InstantSource clock;

	/** Hasta cuándo se deja descansar a Redis, en milisegundos desde la época. */
	private volatile long restingUntil;

	public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties, MeterRegistry registry,
			InstantSource clock) {
		this.redis = redis;
		this.properties = properties;
		this.clock = clock;
		this.unavailable = Counter.builder("einvoice.ratelimit.unavailable")
			.description("Peticiones que pasaron sin contarse porque Redis no respondió")
			.register(registry);
	}

	/** Cuenta la petición del usuario y dice si cabe en la ventana actual. */
	public Decision check(String subject, boolean write) {
		long millis = clock.instant().toEpochMilli();
		if (millis < restingUntil) {
			// Redis acaba de fallar: cada petición no vuelve a esperar a que agote su plazo.
			unavailable.increment();
			return new Decision(true, 0);
		}
		long now = millis / 1000;
		long window = now / WINDOW_SECONDS;
		String user = digest(subject);
		try {
			// Una escritura rechazada no gasta además el cupo general.
			if (write && count("w:" + user + ":" + window) > properties.writesPerMinute()) {
				return new Decision(false, WINDOW_SECONDS - now % WINDOW_SECONDS);
			}
			if (count("r:" + user + ":" + window) > properties.requestsPerMinute()) {
				return new Decision(false, WINDOW_SECONDS - now % WINDOW_SECONDS);
			}
			return new Decision(true, 0);
		}
		catch (RuntimeException ex) {
			restingUntil = millis + REST_AFTER_FAILURE.toMillis();
			unavailable.increment();
			log.debug("Límite de peticiones sin aplicar, Redis no responde: {}", ex.toString());
			return new Decision(true, 0);
		}
	}

	private long count(String suffix) {
		String key = "einvoice:rl:" + suffix;
		Long value = redis.opsForValue().increment(key);
		if (value != null && value == 1L) {
			redis.expire(key, KEY_LIFETIME);
		}
		return value == null ? 0 : value;
	}

	/** La clave no lleva el identificador del usuario tal cual. */
	private static String digest(String subject) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 16);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
