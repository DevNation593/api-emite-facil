package com.example.einvoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;

/** Las reglas de unicidad fiscal y pertenencia a un tenant se sostienen en la base, no solo en la aplicación. */
class IdentitySchemaIT extends AbstractIntegrationTest {

	@Test
	void rucIsUniqueAcrossTenants() {
		String ruc = TestData.ruc();
		TestData.taxpayer(jdbc, TestData.tenant(jdbc), ruc);
		UUID otherTenant = TestData.tenant(jdbc);
		assertThatThrownBy(() -> TestData.taxpayer(jdbc, otherTenant, ruc))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rucMustBeThirteenDigits() {
		UUID tenant = TestData.tenant(jdbc);
		assertThatThrownBy(() -> TestData.taxpayer(jdbc, tenant, "179001234500"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> TestData.taxpayer(jdbc, tenant, "17900123450AB"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void establishmentCannotPointToTaxpayerOfAnotherTenant() {
		UUID taxpayerOfA = TestData.taxpayer(jdbc, TestData.tenant(jdbc), TestData.ruc());
		UUID tenantB = TestData.tenant(jdbc);
		assertThatThrownBy(() -> TestData.establishment(jdbc, tenantB, taxpayerOfA, "001"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void emissionPointCannotPointToEstablishmentOfAnotherTenant() {
		UUID tenantA = TestData.tenant(jdbc);
		UUID establishmentOfA = TestData.establishment(jdbc, tenantA,
				TestData.taxpayer(jdbc, tenantA, TestData.ruc()), "001");
		UUID tenantB = TestData.tenant(jdbc);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO emission_points (tenant_id, establishment_id, code) VALUES (?, ?, '001')", tenantB,
				establishmentOfA))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void establishmentCodeIsUniquePerTaxpayerAndKeepsLeadingZeros() {
		UUID tenant = TestData.tenant(jdbc);
		UUID taxpayer = TestData.taxpayer(jdbc, tenant, TestData.ruc());
		UUID establishment = TestData.establishment(jdbc, tenant, taxpayer, "001");
		assertThat(jdbc.queryForObject("SELECT code FROM establishments WHERE id = ?", String.class, establishment))
			.isEqualTo("001");
		assertThatThrownBy(() -> TestData.establishment(jdbc, tenant, taxpayer, "001"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void membershipIsUniquePerUserAndTenant() {
		UUID tenant = TestData.tenant(jdbc);
		UUID user = TestData.user(jdbc);
		jdbc.update("INSERT INTO memberships (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", user, tenant);
		assertThatThrownBy(() -> jdbc
			.update("INSERT INTO memberships (user_id, tenant_id, role) VALUES (?, ?, 'ADMIN')", user, tenant))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void roleAndEnvironmentAreConstrained() {
		UUID tenant = TestData.tenant(jdbc);
		UUID user = TestData.user(jdbc);
		assertThatThrownBy(() -> jdbc
			.update("INSERT INTO memberships (user_id, tenant_id, role) VALUES (?, ?, 'ROOT')", user, tenant))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO taxpayers (tenant_id, ruc, legal_name,"
				+ " head_office_address, default_environment) VALUES (?, ?, 'X', 'Quito', '3')", tenant,
				TestData.ruc()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
