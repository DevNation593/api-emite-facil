package com.example.einvoice.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;

class SequenceApiIT extends AbstractIntegrationTest {

	@Autowired
	SequenceService sequences;

	@Autowired
	PlatformTransactionManager transactionManager;

	String owner;

	UUID tenant;

	UUID taxpayer;

	UUID point;

	@BeforeEach
	void emissionPoint() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
		taxpayer = createTaxpayer(owner, TestData.ruc());
		point = createEmissionPoint(owner, createEstablishment(owner, taxpayer, "001"), "001");
	}

	static String path(UUID emissionPoint) {
		return "/api/v1/emission-points/" + emissionPoint + "/sequence";
	}

	static String body(String environment, String documentType, long nextValue) {
		return "{\"environment\":\"" + environment + "\",\"documentType\":\"" + documentType + "\",\"nextValue\":"
				+ nextValue + "}";
	}

	@Test
	void ownerSetsInitialSequence() throws Exception {
		mvc.perform(json(put(path(point)), owner, body("1", "01", 4521)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.emissionPointId").value(point.toString()))
			.andExpect(jsonPath("$.environment").value("1"))
			.andExpect(jsonPath("$.documentType").value("01"))
			.andExpect(jsonPath("$.nextValue").value(4521));

		Integer reserved = new TransactionTemplate(transactionManager)
			.execute(status -> sequences.reserve(tenant, point, "1", "01", LocalDate.of(2026, 10, 10)));
		assertThat(reserved).isEqualTo(4521);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'SEQUENCE_SET' AND entity_id = ? AND tenant_id = ?",
				Integer.class, point, tenant))
			.isEqualTo(1);
	}

	@Test
	void sequenceCanBeCorrectedBeforeFirstDocument() throws Exception {
		mvc.perform(json(put(path(point)), owner, body("1", "01", 10))).andExpect(status().isOk());
		mvc.perform(json(put(path(point)), owner, body("1", "01", 20)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nextValue").value(20));
	}

	@Test
	void usedSequenceCannotBeReset() throws Exception {
		UUID customer = TestData.customer(jdbc, tenant, "05", "1712345678");
		TestData.document(jdbc, tenant, taxpayer, point, customer, 1, TestData.accessKey());

		mvc.perform(json(put(path(point)), owner, body("1", "01", 500)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("SEQUENCE_ALREADY_USED"));
		mvc.perform(json(put(path(point)), owner, body("2", "01", 500))).andExpect(status().isOk());
	}

	@Test
	void billerCannotSetSequence() throws Exception {
		mvc.perform(json(put(path(point)), memberToken(tenant, "BILLER"), body("1", "01", 10)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
	}

	@Test
	void rejectsOutOfRangeAndUnknownType() throws Exception {
		for (String invalid : new String[] { body("1", "01", 0), body("1", "01", 1_000_000_000L), body("1", "04", 5),
				body("3", "01", 5), "{\"environment\":\"1\",\"documentType\":\"01\"}" }) {
			mvc.perform(json(put(path(point)), owner, invalid))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}
	}

	@Test
	void emissionPointOfAnotherTenantIsNotFound() throws Exception {
		String other = newUserToken();
		createTenant(other);
		mvc.perform(json(put(path(point)), other, body("1", "01", 10)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
		mvc.perform(json(put(path(UUID.randomUUID())), owner, body("1", "01", 10))).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM document_sequences WHERE emission_point_id = ?",
				Integer.class, point))
			.isZero();
	}

}
