package com.example.einvoice.sequence;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Secuenciales de comprobantes: un contador por punto de emisión, ambiente y tipo de documento. La
 * reserva bloquea la fila del contador hasta que termina la transacción que emite el comprobante,
 * así que dos emisiones simultáneas nunca reciben el mismo número y una emisión que falla lo devuelve.
 */
@Service
public class SequenceService {

	/** Valor de next_value cuando ya se entregó el 999999999. */
	private static final long EXHAUSTED = 1_000_000_000L;

	private static final String KEY = "emission_point_id = :point AND environment = :environment"
			+ " AND document_type = :documentType";

	public record SequenceView(UUID emissionPointId, String environment, String documentType, long nextValue) {
	}

	private record Counter(long nextValue, LocalDate lastIssueDate) {
	}

	private final JdbcClient jdbc;

	private final AuditLog audit;

	public SequenceService(JdbcClient jdbc, AuditLog audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	/**
	 * Entrega el siguiente secuencial dentro de la transacción en curso. La fecha de emisión no puede
	 * ser anterior a la del último comprobante del contador (orden cronológico, ficha 9.18).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int reserve(UUID tenantId, UUID emissionPointId, String environment, String documentType,
			LocalDate issueDate) {
		jdbc.sql("""
				INSERT INTO document_sequences (tenant_id, emission_point_id, environment, document_type)
				VALUES (:tenant, :point, :environment, :documentType)
				ON CONFLICT (emission_point_id, environment, document_type) DO NOTHING
				""")
			.param("tenant", tenantId)
			.param("point", emissionPointId)
			.param("environment", environment)
			.param("documentType", documentType)
			.update();
		Counter counter = jdbc
			.sql("SELECT next_value, last_issue_date FROM document_sequences WHERE " + KEY + " FOR UPDATE")
			.param("point", emissionPointId)
			.param("environment", environment)
			.param("documentType", documentType)
			.query(Counter.class)
			.single();
		if (counter.nextValue() >= EXHAUSTED) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SEQUENCE_EXHAUSTED",
					"El punto de emisión agotó sus secuenciales para este tipo de comprobante");
		}
		if (counter.lastIssueDate() != null && issueDate.isBefore(counter.lastIssueDate())) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ISSUE_DATE",
					"La fecha de emisión no puede ser anterior a la del último comprobante del punto de emisión");
		}
		jdbc.sql("UPDATE document_sequences SET next_value = next_value + 1, last_issue_date = :issueDate WHERE " + KEY)
			.param("issueDate", issueDate)
			.param("point", emissionPointId)
			.param("environment", environment)
			.param("documentType", documentType)
			.update();
		return (int) counter.nextValue();
	}

	/** Fija el próximo secuencial de un contador que todavía no ha emitido ningún comprobante. */
	@Transactional
	public SequenceView set(TenantAccess access, UUID emissionPointId, String environment, String documentType,
			int nextValue) {
		access.requireWrite();
		boolean exists = jdbc.sql("SELECT count(*) FROM emission_points WHERE id = :point AND tenant_id = :tenant")
			.param("point", emissionPointId)
			.param("tenant", access.tenantId())
			.query(Long.class)
			.single() > 0;
		if (!exists) {
			throw ApiException.notFound();
		}
		// El upsert toma el bloqueo del contador antes de mirar si ya se usó: una emisión en curso
		// termina primero y su comprobante ya es visible para la comprobación siguiente.
		jdbc.sql("""
				INSERT INTO document_sequences (tenant_id, emission_point_id, environment, document_type, next_value)
				VALUES (:tenant, :point, :environment, :documentType, :nextValue)
				ON CONFLICT (emission_point_id, environment, document_type)
				DO UPDATE SET next_value = EXCLUDED.next_value
				""")
			.param("tenant", access.tenantId())
			.param("point", emissionPointId)
			.param("environment", environment)
			.param("documentType", documentType)
			.param("nextValue", nextValue)
			.update();
		boolean used = jdbc.sql("SELECT count(*) FROM fiscal_documents WHERE " + KEY)
			.param("point", emissionPointId)
			.param("environment", environment)
			.param("documentType", documentType)
			.query(Long.class)
			.single() > 0;
		if (used) {
			throw new ApiException(HttpStatus.CONFLICT, "SEQUENCE_ALREADY_USED",
					"El punto de emisión ya emitió comprobantes de este tipo; su secuencial no se puede cambiar");
		}
		audit.record(access.tenantId(), access.userId(), "SEQUENCE_SET", "EMISSION_POINT", emissionPointId,
				Map.of("environment", environment, "documentType", documentType, "nextValue", nextValue));
		return new SequenceView(emissionPointId, environment, documentType, nextValue);
	}

}
