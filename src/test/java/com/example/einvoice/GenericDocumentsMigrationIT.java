package com.example.einvoice;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.einvoice.BillingSchemaIT.Scenario;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;

/** El sobre común de {@code fiscal_documents} admite todos los tipos y rechaza las combinaciones incoherentes. */
class GenericDocumentsMigrationIT extends AbstractIntegrationTest {

	private static final String CONTENT = "{}";

	private UUID insert(Scenario s, int sequence, String type, BigDecimal total, String content) {
		return TestData.document(jdbc, s.tenant(), s.taxpayer(), s.point(), s.customer(), sequence,
				TestData.accessKey(), type, total, content);
	}

	@Test
	void onlyKnownDocumentTypes() {
		Scenario s = Scenario.create(jdbc);
		assertThatThrownBy(() -> insert(s, 1, "02", BigDecimal.ONE, CONTENT))
			.isInstanceOf(DataIntegrityViolationException.class);
		insert(s, 2, "03", BigDecimal.ONE, CONTENT);
	}

	@Test
	void onlyRemissionGuideHasNoTotal() {
		Scenario s = Scenario.create(jdbc);
		insert(s, 1, "06", null, CONTENT);
		assertThatThrownBy(() -> insert(s, 2, "06", BigDecimal.ONE, CONTENT))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(s, 3, "04", null, CONTENT))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void contentOnlyOutsideInvoices() {
		Scenario s = Scenario.create(jdbc);
		assertThatThrownBy(() -> insert(s, 1, "01", BigDecimal.ONE, CONTENT))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(s, 2, "05", BigDecimal.ONE, null))
			.isInstanceOf(DataIntegrityViolationException.class);
		insert(s, 3, "01", BigDecimal.ONE, null);
		insert(s, 4, "05", BigDecimal.ONE, CONTENT);
	}

	@Test
	void invoiceKeepsItsTotals() {
		Scenario s = Scenario.create(jdbc);
		UUID invoice = insert(s, 1, "01", BigDecimal.ONE, null);
		assertThatThrownBy(() -> jdbc.update("UPDATE fiscal_documents SET subtotal = NULL WHERE id = ?", invoice))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE fiscal_documents SET total_tax = NULL WHERE id = ?", invoice))
			.isInstanceOf(DataIntegrityViolationException.class);
		UUID liquidation = insert(s, 2, "03", BigDecimal.ONE, CONTENT);
		jdbc.update("UPDATE fiscal_documents SET subtotal = NULL WHERE id = ?", liquidation);
	}

	@Test
	void modifiedDocumentIsCompleteOrAbsent() {
		Scenario s = Scenario.create(jdbc);
		UUID invoice = insert(s, 1, "01", BigDecimal.ONE, null);
		UUID note = insert(s, 2, "04", BigDecimal.ONE, CONTENT);
		jdbc.update("""
				UPDATE fiscal_documents SET modified_document_id = ?, modified_doc_code = '01',
				 modified_doc_number = '001-001-000000001', modified_doc_date = DATE '2026-10-01' WHERE id = ?
				""", invoice, note);
		UUID other = insert(s, 3, "04", BigDecimal.ONE, CONTENT);
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE fiscal_documents SET modified_doc_number = '001-001-000000001' WHERE id = ?", other))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE fiscal_documents SET modified_document_id = ? WHERE id = ?",
				invoice, other))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				UPDATE fiscal_documents SET modified_doc_code = '01', modified_doc_number = '1-1-1',
				 modified_doc_date = DATE '2026-10-01' WHERE id = ?
				""", other))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void modifiedDocumentStaysInTenant() {
		Scenario a = Scenario.create(jdbc);
		Scenario b = Scenario.create(jdbc);
		UUID foreignInvoice = insert(b, 1, "01", BigDecimal.ONE, null);
		UUID ownInvoice = insert(a, 1, "01", BigDecimal.ONE, null);
		UUID note = insert(a, 2, "04", BigDecimal.ONE, CONTENT);
		String sql = """
				UPDATE fiscal_documents SET modified_document_id = ?, modified_doc_code = '01',
				 modified_doc_number = '001-001-000000001', modified_doc_date = DATE '2026-10-01' WHERE id = ?
				""";
		assertThatThrownBy(() -> jdbc.update(sql, foreignInvoice, note))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(sql, note, note)).isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update(sql, ownInvoice, note);
	}

}
