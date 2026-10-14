package com.example.einvoice;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;

/** Unicidad de secuencial, clave de acceso e idempotencia, y pertenencia al tenant, sostenidas en la base. */
class BillingSchemaIT extends AbstractIntegrationTest {

	@Test
	void customerIdentificationIsUniquePerTenant() {
		UUID tenant = TestData.tenant(jdbc);
		TestData.customer(jdbc, tenant, "05", "1712345678");
		assertThatThrownBy(() -> TestData.customer(jdbc, tenant, "05", "1712345678"))
			.isInstanceOf(DataIntegrityViolationException.class);
		TestData.customer(jdbc, TestData.tenant(jdbc), "05", "1712345678");
	}

	@Test
	void productSkuIsUniquePerTenant() {
		UUID tenant = TestData.tenant(jdbc);
		TestData.product(jdbc, tenant, "SKU-1", "4");
		assertThatThrownBy(() -> TestData.product(jdbc, tenant, "SKU-1", "4"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> TestData.product(jdbc, tenant, "SKU-2", "9"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void documentCannotReferenceCustomerOfAnotherTenant() {
		Scenario a = Scenario.create(jdbc);
		UUID foreignCustomer = TestData.customer(jdbc, TestData.tenant(jdbc), "05", "1712345678");
		assertThatThrownBy(() -> TestData.document(jdbc, a.tenant, a.taxpayer, a.point, foreignCustomer, 1,
				TestData.accessKey()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sequenceAndAccessKeyAreUnique() {
		Scenario a = Scenario.create(jdbc);
		String key = TestData.accessKey();
		TestData.document(jdbc, a.tenant, a.taxpayer, a.point, a.customer, 1, key);
		assertThatThrownBy(
				() -> TestData.document(jdbc, a.tenant, a.taxpayer, a.point, a.customer, 1, TestData.accessKey()))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> TestData.document(jdbc, a.tenant, a.taxpayer, a.point, a.customer, 2, key))
			.isInstanceOf(DataIntegrityViolationException.class);
		TestData.document(jdbc, a.tenant, a.taxpayer, a.point, a.customer, 2, TestData.accessKey());
	}

	@Test
	void idempotencyKeyIsUniquePerTenant() {
		UUID tenant = TestData.tenant(jdbc);
		String sql = "INSERT INTO idempotency_records (tenant_id, idempotency_key, request_hash) VALUES (?, ?, ?)";
		jdbc.update(sql, tenant, "k-1", "a".repeat(64));
		assertThatThrownBy(() -> jdbc.update(sql, tenant, "k-1", "b".repeat(64)))
			.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update(sql, TestData.tenant(jdbc), "k-1", "a".repeat(64));
	}

	record Scenario(UUID tenant, UUID taxpayer, UUID point, UUID customer) {

		static Scenario create(org.springframework.jdbc.core.JdbcTemplate jdbc) {
			UUID tenant = TestData.tenant(jdbc);
			UUID taxpayer = TestData.taxpayer(jdbc, tenant, TestData.ruc());
			UUID establishment = TestData.establishment(jdbc, tenant, taxpayer, "001");
			UUID point = TestData.emissionPoint(jdbc, tenant, establishment, "001");
			return new Scenario(tenant, taxpayer, point, TestData.customer(jdbc, tenant, "05", "1712345678"));
		}

	}

}
