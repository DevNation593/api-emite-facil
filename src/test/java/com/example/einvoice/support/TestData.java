package com.example.einvoice.support;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.JdbcTemplate;

/** Datos sintéticos e inserciones directas para preparar escenarios. */
public final class TestData {

	private TestData() {
	}

	/** RUC sintáctico: diez dígitos aleatorios y el sufijo 001 que exige el XSD del SRI al emisor. */
	public static String ruc() {
		return String.format(Locale.ROOT, "%010d", ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L))
				+ "001";
	}

	public static UUID tenant(JdbcTemplate jdbc) {
		return jdbc.queryForObject("INSERT INTO tenants (name) VALUES (?) RETURNING id", UUID.class,
				"Empresa " + UUID.randomUUID());
	}

	public static UUID user(JdbcTemplate jdbc) {
		return jdbc.queryForObject("INSERT INTO users (issuer, subject) VALUES ('https://idp.test', ?) RETURNING id",
				UUID.class, "sub-" + UUID.randomUUID());
	}

	public static UUID taxpayer(JdbcTemplate jdbc, UUID tenantId, String ruc) {
		return jdbc.queryForObject("INSERT INTO taxpayers (tenant_id, ruc, legal_name, head_office_address,"
				+ " default_environment) VALUES (?, ?, 'Emisor de prueba', 'Quito', '1') RETURNING id", UUID.class,
				tenantId, ruc);
	}

	public static UUID establishment(JdbcTemplate jdbc, UUID tenantId, UUID taxpayerId, String code) {
		return jdbc.queryForObject(
				"INSERT INTO establishments (tenant_id, taxpayer_id, code, address) VALUES (?, ?, ?, 'Quito') RETURNING id",
				UUID.class, tenantId, taxpayerId, code);
	}

	public static UUID emissionPoint(JdbcTemplate jdbc, UUID tenantId, UUID establishmentId, String code) {
		return jdbc.queryForObject(
				"INSERT INTO emission_points (tenant_id, establishment_id, code) VALUES (?, ?, ?) RETURNING id",
				UUID.class, tenantId, establishmentId, code);
	}

	public static UUID customer(JdbcTemplate jdbc, UUID tenantId, String idType, String idNumber) {
		return jdbc.queryForObject(
				"INSERT INTO customers (tenant_id, id_type, id_number, name) VALUES (?, ?, ?, 'Cliente de prueba') RETURNING id",
				UUID.class, tenantId, idType, idNumber);
	}

	public static UUID product(JdbcTemplate jdbc, UUID tenantId, String sku, String ivaRateCode) {
		return jdbc.queryForObject("INSERT INTO products (tenant_id, sku, name, unit_price, iva_rate_code)"
				+ " VALUES (?, ?, 'Producto de prueba', 10, ?) RETURNING id", UUID.class, tenantId, sku, ivaRateCode);
	}

	/** Clave de acceso sintáctica (49 dígitos) para pruebas de esquema; no pretende ser válida. */
	public static String accessKey() {
		StringBuilder key = new StringBuilder(49);
		for (int i = 0; i < 49; i++) {
			key.append(ThreadLocalRandom.current().nextInt(10));
		}
		return key.toString();
	}

	public static UUID document(JdbcTemplate jdbc, UUID tenantId, UUID taxpayerId, UUID emissionPointId,
			UUID customerId, int sequence, String accessKey) {
		UUID user = user(jdbc);
		return jdbc.queryForObject("""
				INSERT INTO fiscal_documents (tenant_id, taxpayer_id, emission_point_id, customer_id, document_type,
				 environment, issuer_ruc, establishment_code, emission_point_code, sequence, access_key, state,
				 issue_date, currency, customer_id_type, customer_id_number, customer_name, subtotal, total_discount,
				 total_tax, total, created_by, issuer_legal_name, issuer_head_office_address, establishment_address,
				 issuer_accounting_required, issuer_rimpe_regime)
				VALUES (?, ?, ?, ?, '01', '1', '1790012345001', '001', '001', ?, ?, 'RESERVED', DATE '2026-10-10',
				 'USD', '05', '1712345678', 'Cliente de prueba', 10, 0, 1.5, 11.5, ?, 'Emisor de prueba', 'Quito',
				 'Quito', false, 'NONE')
				RETURNING id
				""", UUID.class, tenantId, taxpayerId, emissionPointId, customerId, sequence, accessKey, user);
	}

}
