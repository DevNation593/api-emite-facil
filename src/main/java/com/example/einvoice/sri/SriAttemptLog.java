package com.example.einvoice.sri;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Bitácora de las llamadas al SRI. Un intento se abre antes de llamar y se cierra con su resultado;
 * mientras está abierto (outcome nulo) la llamada se considera en curso.
 */
@Component
public class SriAttemptLog {

	public static final String RECEPTION = "RECEPTION";

	public static final String AUTHORIZATION = "AUTHORIZATION";

	public record MessageView(String identifier, String message, String additionalInfo, String type) {
	}

	public record AttemptView(UUID id, String operation, String outcome, String sriStatus, Integer httpStatus,
			OffsetDateTime startedAt, OffsetDateTime completedAt, List<MessageView> messages) {
	}

	private record AttemptRow(UUID id, String operation, String outcome, String sriStatus, Integer httpStatus,
			OffsetDateTime startedAt, OffsetDateTime completedAt) {
	}

	private record MessageRow(UUID attemptId, String identifier, String message, String additionalInfo, String type) {
	}

	private record Closed(String operation, String environment, double seconds) {
	}

	private final JdbcClient jdbc;

	private final MeterRegistry metrics;

	public SriAttemptLog(JdbcClient jdbc, MeterRegistry metrics) {
		this.jdbc = jdbc;
		this.metrics = metrics;
	}

	/** Abre un intento y devuelve su id. */
	public UUID start(UUID tenantId, UUID documentId, String operation, SriEnvironment environment) {
		return jdbc.sql("""
				INSERT INTO sri_attempts (tenant_id, document_id, operation, environment)
				VALUES (:tenantId, :documentId, :operation, :environment) RETURNING id
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("operation", operation)
			.param("environment", environment.code())
			.query(UUID.class)
			.single();
	}

	/**
	 * Cierra el intento con el resultado de la recepción. Devuelve false si ya estaba cerrado (otro
	 * proceso lo dio por abandonado): en ese caso el resultado no debe aplicarse al documento.
	 */
	public boolean complete(UUID attemptId, String outcome, ReceptionResult result) {
		return complete(attemptId, outcome, result.sriStatus(), result.httpStatus(), result.errorDetail(),
				result.rawResponse(), result.messages());
	}

	/** Cierra el intento con el resultado de la autorización; la respuesta se guarda solo si se pide. */
	public boolean complete(UUID attemptId, String outcome, AuthorizationResult result, boolean keepResponse) {
		return complete(attemptId, outcome, result.sriStatus(), result.httpStatus(), result.errorDetail(),
				keepResponse ? result.rawResponse() : null, result.messages());
	}

	private boolean complete(UUID attemptId, String outcome, String sriStatus, Integer httpStatus, String errorDetail,
			byte[] response, List<SriMessage> messages) {
		Optional<Closed> closed = jdbc.sql("""
				UPDATE sri_attempts
				SET outcome = :outcome, sri_status = :sriStatus, http_status = :httpStatus,
				 error_detail = :errorDetail, response = :response, completed_at = clock_timestamp()
				WHERE id = :id AND outcome IS NULL
				RETURNING operation, environment, extract(epoch FROM (completed_at - started_at)) AS seconds
				""")
			.param("outcome", outcome)
			.param("sriStatus", sriStatus)
			.param("httpStatus", httpStatus, java.sql.Types.INTEGER)
			.param("errorDetail", errorDetail)
			.param("response", response, java.sql.Types.BINARY)
			.param("id", attemptId)
			.query(Closed.class)
			.optional();
		if (closed.isEmpty()) {
			return false;
		}
		// Cuánto tardó el SRI y qué respondió, por operación y ambiente: sin datos del comprobante.
		Timer.builder("einvoice.sri.calls")
			.description("Llamadas al SRI")
			.tag("operation", closed.get().operation())
			.tag("environment", closed.get().environment())
			.tag("outcome", outcome)
			.register(metrics)
			.record(Duration.ofNanos(Math.max(0L, (long) (closed.get().seconds() * 1_000_000_000L))));
		for (int i = 0; i < messages.size(); i++) {
			SriMessage message = messages.get(i);
			jdbc.sql("""
					INSERT INTO sri_messages (attempt_id, position, identifier, message, additional_info, type)
					VALUES (:attemptId, :position, :identifier, :message, :additionalInfo, :type)
					""")
				.param("attemptId", attemptId)
				.param("position", i + 1)
				.param("identifier", message.identifier())
				.param("message", message.message())
				.param("additionalInfo", message.additionalInfo())
				.param("type", message.type())
				.update();
		}
		return true;
	}

	/** Si hay un envío a la recepción abierto hace menos del plazo dado: sigue en curso. */
	public boolean sendInFlight(UUID documentId, Duration lease) {
		return jdbc.sql("""
				SELECT count(*) FROM sri_attempts
				WHERE document_id = :documentId AND operation = 'RECEPTION' AND outcome IS NULL
				 AND started_at > now() - make_interval(secs => CAST(:seconds AS double precision))
				""").param("documentId", documentId).param("seconds", lease.toMillis() / 1000.0).query(Long.class).single() > 0;
	}

	/** Da por abandonados los envíos a la recepción que quedaron abiertos. */
	public int abandonSends(UUID documentId) {
		return jdbc.sql("""
				UPDATE sri_attempts SET outcome = 'ABANDONED', completed_at = clock_timestamp()
				WHERE document_id = :documentId AND operation = 'RECEPTION' AND outcome IS NULL
				""").param("documentId", documentId).update();
	}

	/** Intentos del documento con sus mensajes, el más reciente primero. */
	public List<AttemptView> list(UUID tenantId, UUID documentId) {
		List<AttemptRow> attempts = jdbc.sql("""
				SELECT id, operation, outcome, sri_status, http_status, started_at, completed_at FROM sri_attempts
				WHERE tenant_id = :tenantId AND document_id = :documentId ORDER BY started_at DESC, id
				""").param("tenantId", tenantId).param("documentId", documentId).query(AttemptRow.class).list();
		List<MessageRow> messages = jdbc.sql("""
				SELECT m.attempt_id, m.identifier, m.message, m.additional_info, m.type
				FROM sri_messages m JOIN sri_attempts a ON a.id = m.attempt_id
				WHERE a.tenant_id = :tenantId AND a.document_id = :documentId ORDER BY m.attempt_id, m.position
				""").param("tenantId", tenantId).param("documentId", documentId).query(MessageRow.class).list();
		List<AttemptView> result = new ArrayList<>();
		for (AttemptRow attempt : attempts) {
			List<MessageView> own = messages.stream()
				.filter(message -> message.attemptId().equals(attempt.id()))
				.map(message -> new MessageView(message.identifier(), message.message(), message.additionalInfo(),
						message.type()))
				.toList();
			result.add(new AttemptView(attempt.id(), attempt.operation(), attempt.outcome(), attempt.sriStatus(),
					attempt.httpStatus(), attempt.startedAt(), attempt.completedAt(), own));
		}
		return result;
	}

}
