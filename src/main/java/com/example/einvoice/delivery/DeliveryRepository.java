package com.example.einvoice.delivery;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Entregas de los comprobantes. Cada cambio de estado es una sola sentencia: reservar una entrega
 * para enviarla es atómico, así que dos procesos nunca envían la misma a la vez.
 */
@Repository
public class DeliveryRepository {

	public static final String EMAIL = "EMAIL";

	public record DeliveryRow(UUID id, String channel, String recipient, String status, int attemptCount,
			String lastError, OffsetDateTime createdAt, OffsetDateTime sentAt, UUID requestedBy) {
	}

	/** Entrega reservada para enviar ahora. */
	public record Claim(String recipient, int attemptCount) {
	}

	private final JdbcClient jdbc;

	public DeliveryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Crea la entrega automática del comprobante si aún no existe. Devuelve si la creó. */
	public boolean ensureAutomatic(UUID tenantId, UUID documentId, String recipient) {
		return jdbc.sql("""
				INSERT INTO deliveries (tenant_id, document_id, channel, recipient, status, next_attempt_at)
				VALUES (:tenantId, :documentId, 'EMAIL', :recipient, 'PENDING', now())
				ON CONFLICT (document_id, channel) WHERE requested_by IS NULL DO NOTHING
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("recipient", recipient)
			.update() == 1;
	}

	/** Crea una entrega pedida por un usuario. */
	public UUID create(UUID tenantId, UUID documentId, String recipient, UUID requestedBy) {
		return jdbc.sql("""
				INSERT INTO deliveries (tenant_id, document_id, channel, recipient, status, next_attempt_at,
				 requested_by)
				VALUES (:tenantId, :documentId, 'EMAIL', :recipient, 'PENDING', now(), :requestedBy) RETURNING id
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("recipient", recipient)
			.param("requestedBy", requestedBy)
			.query(UUID.class)
			.single();
	}

	/**
	 * Entregas del comprobante que se pueden intentar ahora: pendientes cuya espera ya pasó, o con un
	 * envío que se quedó a medias.
	 */
	public List<UUID> pending(UUID tenantId, UUID documentId, Duration lease) {
		return jdbc.sql("""
				SELECT id FROM deliveries
				WHERE tenant_id = :tenantId AND document_id = :documentId
				 AND ((status = 'PENDING' AND (retry_after IS NULL OR retry_after <= now()))
				  OR (status = 'SENDING'
				   AND sending_since < now() - make_interval(secs => CAST(:lease AS double precision))))
				ORDER BY created_at, id
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("lease", seconds(lease))
			.query(UUID.class)
			.list();
	}

	/**
	 * Reserva la entrega para enviarla. Vacío si otro proceso se adelantó o si aún no ha pasado la
	 * espera de su reintento. Queda programada al vencer el plazo de envío: si este proceso muere
	 * antes de anotar el resultado, se retoma entonces. El número de intento que devuelve identifica
	 * esta reserva al anotar un fallo.
	 */
	public Optional<Claim> claim(UUID deliveryId, Duration lease) {
		return jdbc.sql("""
				UPDATE deliveries
				SET status = 'SENDING', sending_since = now(), attempt_count = attempt_count + 1,
				 next_attempt_at = now() + make_interval(secs => CAST(:lease AS double precision))
				WHERE id = :id AND ((status = 'PENDING' AND (retry_after IS NULL OR retry_after <= now()))
				 OR (status = 'SENDING'
				  AND sending_since < now() - make_interval(secs => CAST(:lease AS double precision))))
				RETURNING recipient, attempt_count
				""").param("id", deliveryId).param("lease", seconds(lease)).query(Claim.class).optional();
	}

	/**
	 * Anota que el servidor aceptó el mensaje. Vale aunque otro intento haya tomado después la
	 * entrega: el correo ya salió, y anotarlo evita que se repita. Devuelve si es la primera vez que
	 * se anota.
	 */
	public boolean markSent(UUID deliveryId, String providerMessageId) {
		return jdbc.sql("""
				UPDATE deliveries
				SET status = 'SENT', sent_at = now(), provider_message_id = :messageId, next_attempt_at = NULL,
				 retry_after = NULL, sending_since = NULL, last_error = NULL
				WHERE id = :id AND status <> 'SENT'
				""").param("messageId", providerMessageId).param("id", deliveryId).update() == 1;
	}

	/**
	 * Anota un fallo temporal del intento indicado y la espera antes del siguiente. No hace nada si
	 * ese intento ya no es el vigente: otro proceso tomó la entrega y su resultado manda.
	 */
	public void markRetry(UUID deliveryId, int attempt, Duration delay, String error) {
		jdbc.sql("""
				UPDATE deliveries
				SET status = 'PENDING', sending_since = NULL, last_error = :error,
				 retry_after = now() + make_interval(secs => CAST(:delay AS double precision)),
				 next_attempt_at = now() + make_interval(secs => CAST(:delay AS double precision))
				WHERE id = :id AND status = 'SENDING' AND attempt_count = :attempt
				""")
			.param("error", error)
			.param("delay", seconds(delay))
			.param("id", deliveryId)
			.param("attempt", attempt)
			.update();
	}

	/** Anota un fallo definitivo del intento indicado, con la misma salvedad que {@link #markRetry}. */
	public void markFailed(UUID deliveryId, int attempt, String error) {
		jdbc.sql("""
				UPDATE deliveries
				SET status = 'FAILED', sending_since = NULL, next_attempt_at = NULL, retry_after = NULL,
				 last_error = :error
				WHERE id = :id AND status = 'SENDING' AND attempt_count = :attempt
				""").param("error", error).param("id", deliveryId).param("attempt", attempt).update();
	}

	/** Entregas del comprobante, la más reciente primero. */
	public List<DeliveryRow> list(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT id, channel, recipient, status, attempt_count, last_error, created_at, sent_at, requested_by
				FROM deliveries WHERE tenant_id = :tenantId AND document_id = :documentId
				ORDER BY created_at DESC, id
				""").param("tenantId", tenantId).param("documentId", documentId).query(DeliveryRow.class).list();
	}

	public Optional<DeliveryRow> find(UUID tenantId, UUID deliveryId) {
		return jdbc.sql("""
				SELECT id, channel, recipient, status, attempt_count, last_error, created_at, sent_at, requested_by
				FROM deliveries WHERE tenant_id = :tenantId AND id = :id
				""").param("tenantId", tenantId).param("id", deliveryId).query(DeliveryRow.class).optional();
	}

	public int count(UUID documentId) {
		return jdbc.sql("SELECT count(*) FROM deliveries WHERE document_id = :documentId")
			.param("documentId", documentId)
			.query(Integer.class)
			.single();
	}

	private static double seconds(Duration duration) {
		return duration.toMillis() / 1000.0;
	}

}
