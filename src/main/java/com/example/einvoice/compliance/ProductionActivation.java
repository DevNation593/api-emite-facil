package com.example.einvoice.compliance;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.sri.SriEnvironment;
import com.example.einvoice.sri.SriProperties;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Paso de un emisor a producción. Solo ocurre con evidencia que el sistema puede comprobar por sí
 * mismo (certificado vigente y un comprobante que el SRI autorizó en pruebas) y con la declaración
 * de su propietario de que el SRI habilitó ese RUC para producción.
 */
@Service
public class ProductionActivation {

	public static final String CERTIFICATE = "CERTIFICATE";

	public static final String TEST_AUTHORIZATION = "TEST_AUTHORIZATION";

	/**
	 * Qué tiene y qué le falta al emisor. {@code transmissionOpen} dice si la plataforma transmite
	 * hoy a producción: es decisión del proveedor, no un requisito del emisor.
	 */
	public record Readiness(Instant certificateValidUntil, int authorizedTestDocuments, boolean transmissionOpen,
			Instant activatedAt, List<String> missing) {
	}

	private final JdbcClient jdbc;

	private final SriProperties sri;

	private final AuditLog audit;

	public ProductionActivation(JdbcClient jdbc, SriProperties sri, AuditLog audit) {
		this.jdbc = jdbc;
		this.sri = sri;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public Readiness readiness(TenantAccess access, UUID taxpayerId) {
		return evaluate(access.tenantId(), taxpayerId, false);
	}

	@Transactional
	public Readiness activate(TenantAccess access, UUID taxpayerId, boolean sriProductionAuthorized) {
		access.requireOwner();
		// Bloquea al emisor: dos activaciones a la vez dejan un solo evento.
		Readiness current = evaluate(access.tenantId(), taxpayerId, true);
		if (current.activatedAt() != null) {
			return current;
		}
		List<ApiException.FieldError> unmet = new ArrayList<>();
		if (current.missing().contains(CERTIFICATE)) {
			unmet.add(new ApiException.FieldError("certificate", "El emisor no tiene un certificado de firma vigente"));
		}
		if (current.missing().contains(TEST_AUTHORIZATION)) {
			unmet.add(new ApiException.FieldError("testAuthorization",
					"El SRI aún no ha autorizado ningún comprobante de este emisor en el ambiente de pruebas"));
		}
		if (!sriProductionAuthorized) {
			unmet.add(new ApiException.FieldError("sriProductionAuthorized",
					"Debe declarar que el SRI habilitó este RUC para emitir en producción"));
		}
		if (!unmet.isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCTION_REQUIREMENTS_NOT_MET",
					"El emisor todavía no cumple los requisitos para pasar a producción", unmet);
		}
		jdbc.sql("""
				UPDATE taxpayers SET production_enabled_at = now(), production_enabled_by = :user,
				       default_environment = '2'
				 WHERE id = :id AND tenant_id = :tenant
				""").param("user", access.userId()).param("id", taxpayerId).param("tenant", access.tenantId()).update();
		audit.record(access.tenantId(), access.userId(), "TAXPAYER_PRODUCTION_ENABLED", "TAXPAYER", taxpayerId,
				Map.of("certificateValidUntil", current.certificateValidUntil().toString(), "authorizedTestDocuments",
						current.authorizedTestDocuments()));
		return evaluate(access.tenantId(), taxpayerId, false);
	}

	private record TaxpayerRow(OffsetDateTime productionEnabledAt) {
	}

	private Readiness evaluate(UUID tenantId, UUID taxpayerId, boolean lock) {
		// Un emisor de otra empresa se trata igual que uno inexistente.
		TaxpayerRow taxpayer = jdbc
			.sql("SELECT production_enabled_at FROM taxpayers WHERE id = :id AND tenant_id = :tenant"
					+ (lock ? " FOR UPDATE" : ""))
			.param("id", taxpayerId)
			.param("tenant", tenantId)
			.query(TaxpayerRow.class)
			.optional()
			.orElseThrow(ApiException::notFound);
		Optional<OffsetDateTime> certificate = jdbc.sql("""
				SELECT valid_to FROM certificates
				 WHERE tenant_id = :tenant AND taxpayer_id = :taxpayer AND status = 'ACTIVE'
				   AND valid_from <= now() AND valid_to > now()
				""").param("tenant", tenantId).param("taxpayer", taxpayerId).query(OffsetDateTime.class).optional();
		int authorized = jdbc.sql("""
				SELECT count(*) FROM fiscal_documents
				 WHERE tenant_id = :tenant AND taxpayer_id = :taxpayer AND environment = '1' AND state = 'AUTHORIZED'
				""").param("tenant", tenantId).param("taxpayer", taxpayerId).query(Integer.class).single();
		List<String> missing = new ArrayList<>();
		if (certificate.isEmpty()) {
			missing.add(CERTIFICATE);
		}
		if (authorized == 0) {
			missing.add(TEST_AUTHORIZATION);
		}
		return new Readiness(certificate.map(OffsetDateTime::toInstant).orElse(null), authorized,
				sri.enabled(SriEnvironment.PRODUCTION),
				taxpayer.productionEnabledAt() == null ? null : taxpayer.productionEnabledAt().toInstant(),
				List.copyOf(missing));
	}

}
