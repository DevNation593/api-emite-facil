package com.example.einvoice.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Freno contra quien prueba contraseñas: bloquea un correo tras varios fallos seguidos y limita cuántas
 * peticiones públicas hace una dirección IP por minuto. Vive en Redis; si Redis no contesta se deja
 * pasar, como el límite general, y Argon2 sigue haciendo lento cada intento.
 */
public class AuthAttemptLimiter {

	private static final Logger log = LoggerFactory.getLogger(AuthAttemptLimiter.class);

	private static final long WINDOW_SECONDS = 60;

	private static final Duration REST_AFTER_FAILURE = Duration.ofSeconds(10);

	private final StringRedisTemplate redis;

	private final AuthProperties properties;

	private final InstantSource clock;

	private volatile long restingUntil;

	public AuthAttemptLimiter(StringRedisTemplate redis, AuthProperties properties, InstantSource clock) {
		this.redis = redis;
		this.properties = properties;
		this.clock = clock;
	}

	/** Segundos que faltan para que la IP pueda volver a llamar; 0 si puede ahora. Cuenta la petición. */
	public long ipRetryAfter(String ip) {
		long now = clock.instant().getEpochSecond();
		return guarded(() -> {
			String key = "einvoice:auth:ip:" + digest(ip) + ":" + now / WINDOW_SECONDS;
			Long count = redis.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redis.expire(key, Duration.ofSeconds(WINDOW_SECONDS * 2));
			}
			return count != null && count > properties.publicRequestsPerMinute() ? WINDOW_SECONDS - now % WINDOW_SECONDS
					: 0L;
		});
	}

	/** Segundos que le quedan de bloqueo al correo; 0 si no está bloqueado. */
	public long lockedFor(String email) {
		return guarded(() -> {
			Long ttl = redis.getExpire(lockKey(email), TimeUnit.SECONDS);
			return ttl != null && ttl > 0 ? ttl : 0L;
		});
	}

	/** Anota un fallo del correo; al llegar al máximo lo bloquea y dice durante cuántos segundos. */
	public long recordFailure(String email) {
		return guarded(() -> {
			String key = failuresKey(email);
			Long failures = redis.opsForValue().increment(key);
			if (failures != null && failures == 1L) {
				redis.expire(key, properties.lockout());
			}
			if (failures != null && failures >= properties.maxLoginFailures()) {
				redis.opsForValue().set(lockKey(email), "1", properties.lockout());
				redis.delete(key);
				return properties.lockout().toSeconds();
			}
			return 0L;
		});
	}

	/** Un ingreso correcto borra los fallos anteriores del correo. */
	public void recordSuccess(String email) {
		guarded(() -> {
			redis.delete(failuresKey(email));
			return 0L;
		});
	}

	private long guarded(Supplier<Long> action) {
		long millis = clock.instant().toEpochMilli();
		if (millis < restingUntil) {
			return 0;
		}
		try {
			return action.get();
		}
		catch (RuntimeException ex) {
			restingUntil = millis + REST_AFTER_FAILURE.toMillis();
			log.warn("Límite de intentos de ingreso sin aplicar, Redis no responde: {}", ex.toString());
			return 0;
		}
	}

	private static String failuresKey(String email) {
		return "einvoice:auth:fail:" + digest(email);
	}

	private static String lockKey(String email) {
		return "einvoice:auth:lock:" + digest(email);
	}

	/** Las claves no llevan el correo ni la IP tal cual. */
	private static String digest(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 16);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
