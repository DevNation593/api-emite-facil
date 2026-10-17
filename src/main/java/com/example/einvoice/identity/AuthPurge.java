package com.example.einvoice.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Borra, en el worker y cada hora, las sesiones y los enlaces de correo que vencieron hace más de una
 * semana: ya no sirven y solo quedan para mirar qué pasó en los últimos días.
 */
@Component
@ConditionalOnProperty(name = "einvoice.worker.enabled", havingValue = "true", matchIfMissing = true)
class AuthPurge {

	private static final Logger log = LoggerFactory.getLogger(AuthPurge.class);

	private final JdbcClient jdbc;

	AuthPurge(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Scheduled(initialDelayString = "${einvoice.auth.purge-interval-ms:3600000}",
			fixedDelayString = "${einvoice.auth.purge-interval-ms:3600000}")
	void purge() {
		try {
			// Los tokens de renovación caen con su sesión (ON DELETE CASCADE).
			int sessions = jdbc.sql("DELETE FROM auth_sessions WHERE expires_at < now() - interval '7 days'").update();
			int links = jdbc.sql("DELETE FROM user_tokens WHERE expires_at < now() - interval '7 days'").update();
			if (sessions + links > 0) {
				log.info("Autenticación: {} sesiones y {} enlaces vencidos borrados", sessions, links);
			}
		}
		catch (RuntimeException ex) {
			// Base no disponible: la siguiente pasada lo vuelve a intentar.
			log.warn("La purga de sesiones vencidas falló: {}", ex.toString());
		}
	}

}
