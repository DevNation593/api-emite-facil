package com.example.einvoice.webhook;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Avisos pendientes y enviados. Como en las entregas por correo, cada cambio de estado es una sola
 * sentencia: reservar un aviso para enviarlo es atómico, así que dos procesos nunca envían el mismo
 * a la vez.
 */
@Repository
public class WebhookRepository {

	/** Aviso reservado para enviar ahora. El número de intento identifica esta reserva. */
	public record Claim(UUID subscriptionId, UUID eventId, String eventType, String documentState,
			OffsetDateTime occurredAt, int attemptCount) {
	}

	/** A dónde va un aviso y con qué se firma. El secreto anterior solo viene mientras no ha caducado. */
	public record Target(String url, String status, byte[] secret, byte[] previousSecret) {
	}

	/** Lo que el aviso cuenta del comprobante: identificadores y autorización, nada del cliente. */
	public record DocumentFacts(String documentType, String establishmentCode, String emissionPointCode, int sequence,
			String accessKey, String authorizationNumber, OffsetDateTime authorizedAt) {
	}

	private final JdbcClient jdbc;

	public WebhookRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Avisos del comprobante que se pueden intentar ahora: pendientes cuya espera ya pasó, o con un
	 * envío que se quedó a medias.
	 */
	public List<UUID> pending(UUID tenantId, UUID documentId, Duration lease) {
		return jdbc.sql("""
				SELECT id FROM webhook_deliveries
				WHERE tenant_id = :tenantId AND document_id = :documentId
				 AND ((status = 'PENDING' AND (retry_after IS NULL OR retry_after <= now()))
				  OR (status = 'SENDING'
				   AND sending_since < now() - make_interval(secs => CAST(:lease AS double precision))))
				ORDER BY occurred_at, id
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("lease", seconds(lease))
			.query(UUID.class)
			.list();
	}

	/**
	 * Reserva el aviso para enviarlo. Vacío si otro proceso se adelantó o si aún no ha pasado la espera
	 * de su reintento. Queda programado al vencer el plazo de envío: si este proceso muere antes de
	 * anotar el resultado, se retoma entonces.
	 */
	public Optional<Claim> claim(UUID deliveryId, Duration lease) {
		return jdbc.sql("""
				UPDATE webhook_deliveries
				SET status = 'SENDING', sending_since = now(), attempt_count = attempt_count + 1,
				 next_attempt_at = now() + make_interval(secs => CAST(:lease AS double precision))
				WHERE id = :id AND ((status = 'PENDING' AND (retry_after IS NULL OR retry_after <= now()))
				 OR (status = 'SENDING'
				  AND sending_since < now() - make_interval(secs => CAST(:lease AS double precision))))
				RETURNING subscription_id, event_id, event_type, document_state, occurred_at, attempt_count
				""").param("id", deliveryId).param("lease", seconds(lease)).query(Claim.class).optional();
	}

	/**
	 * Anota que el receptor aceptó el aviso. Vale aunque otro intento lo haya tomado después: ya
	 * llegó, y anotarlo evita que se repita.
	 */
	public void markSent(UUID deliveryId, int status) {
		jdbc.sql("""
				UPDATE webhook_deliveries
				SET status = 'SENT', sent_at = now(), last_status = :status, last_error = NULL, next_attempt_at = NULL,
				 retry_after = NULL, sending_since = NULL
				WHERE id = :id AND status <> 'SENT'
				""").param("status", status).param("id", deliveryId).update();
	}

	/**
	 * Anota un fallo temporal del intento indicado y la espera antes del siguiente. No hace nada si ese
	 * intento ya no es el vigente: otro proceso tomó el aviso y su resultado manda.
	 */
	public void markRetry(UUID deliveryId, int attempt, Duration delay, Integer status, String error) {
		jdbc.sql("""
				UPDATE webhook_deliveries
				SET status = 'PENDING', sending_since = NULL, last_status = :status, last_error = :error,
				 retry_after = now() + make_interval(secs => CAST(:delay AS double precision)),
				 next_attempt_at = now() + make_interval(secs => CAST(:delay AS double precision))
				WHERE id = :id AND status = 'SENDING' AND attempt_count = :attempt
				""")
			.param("status", status)
			.param("error", error)
			.param("delay", seconds(delay))
			.param("id", deliveryId)
			.param("attempt", attempt)
			.update();
	}

	/** Anota un fallo definitivo del intento indicado, con la misma salvedad que {@link #markRetry}. */
	public void markFailed(UUID deliveryId, int attempt, Integer status, String error) {
		jdbc.sql("""
				UPDATE webhook_deliveries
				SET status = 'FAILED', sending_since = NULL, next_attempt_at = NULL, retry_after = NULL,
				 last_status = :status, last_error = :error
				WHERE id = :id AND status = 'SENDING' AND attempt_count = :attempt
				""")
			.param("status", status)
			.param("error", error)
			.param("id", deliveryId)
			.param("attempt", attempt)
			.update();
	}

	public Optional<Target> target(UUID tenantId, UUID subscriptionId) {
		return jdbc.sql("""
				SELECT url, status, secret,
				       CASE WHEN previous_secret_expires_at > now() THEN previous_secret END AS previous_secret
				  FROM webhook_subscriptions WHERE tenant_id = :tenantId AND id = :id
				""").param("tenantId", tenantId).param("id", subscriptionId).query(Target.class).optional();
	}

	public Optional<DocumentFacts> document(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT document_type, establishment_code, emission_point_code, sequence, access_key,
				       authorization_number, authorized_at
				  FROM fiscal_documents WHERE tenant_id = :tenantId AND id = :id
				""").param("tenantId", tenantId).param("id", documentId).query(DocumentFacts.class).optional();
	}

	/** Un envío correcto borra la cuenta de fallos seguidos y desde cuándo falla. */
	public void recordSuccess(UUID subscriptionId) {
		jdbc.sql("""
				UPDATE webhook_subscriptions SET consecutive_failures = 0, failing_since = NULL
				 WHERE id = :id AND (consecutive_failures > 0 OR failing_since IS NOT NULL)
				""").param("id", subscriptionId).update();
	}

	/**
	 * Suma un fallo a la suscripción. La apaga si con él llega al tope de fallos seguidos y además
	 * lleva fallando más del tiempo indicado: muchos comprobantes durante una caída corta no bastan.
	 * Devuelve si quedó apagada por este fallo.
	 */
	public boolean recordFailure(UUID subscriptionId, int disableAfter, Duration failingFor) {
		return jdbc.sql("""
				WITH tired AS (
				 SELECT id, consecutive_failures + 1 >= :limit
				        AND failing_since < now() - make_interval(secs => CAST(:failingFor AS double precision)) AS give_up
				   FROM webhook_subscriptions WHERE id = :id AND status = 'ACTIVE' FOR NO KEY UPDATE
				)
				UPDATE webhook_subscriptions s
				   SET consecutive_failures = s.consecutive_failures + 1,
				       failing_since = coalesce(s.failing_since, now()),
				       status = CASE WHEN tired.give_up THEN 'DISABLED' ELSE s.status END,
				       disabled_reason = CASE WHEN tired.give_up THEN :reason ELSE s.disabled_reason END
				  FROM tired WHERE s.id = tired.id
				RETURNING coalesce(tired.give_up, false)
				""")
			.param("limit", disableAfter)
			.param("failingFor", seconds(failingFor))
			.param("reason", WebhookSubscriptionService.DISABLED_BY_FAILURES)
			.param("id", subscriptionId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

	private static double seconds(Duration duration) {
		return duration.toMillis() / 1000.0;
	}

}
