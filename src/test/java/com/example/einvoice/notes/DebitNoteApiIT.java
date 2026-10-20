package com.example.einvoice.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.jayway.jsonpath.JsonPath;

/**
 * Nota de débito por la API: motivos con su IVA y un pago por el total, sobre una factura autorizada de la
 * plataforma (comprador copiado) o sobre una factura escrita a mano.
 */
class DebitNoteApiIT extends AbstractIntegrationTest {

	static final String REASONS = "[{\"reason\":\"Intereses por mora\",\"value\":\"10.00\",\"ivaRateCode\":\"4\"},"
			+ "{\"reason\":\"Gastos de cobranza\",\"value\":\"5.00\",\"ivaRateCode\":\"0\"}]";

	/** 15.00 de motivos + IVA 1.50 de los del 15 %. */
	static final String PAYMENT_16_50 = "[{\"methodCode\":\"20\",\"amount\":\"16.50\"}]";

	BillingScenario s;

	/** Factura autorizada de 2 × 10.00 + IVA 15 % = 23.00. */
	UUID invoice;

	@BeforeEach
	void anAuthorizedInvoice() throws Exception {
		s = newBillingScenario();
		invoice = authorized(issueInvoice(s.owner(), s.invoiceJson("2", "23.00")));
	}

	UUID authorized(UUID document) {
		jdbc.update("UPDATE fiscal_documents SET state = 'AUTHORIZED' WHERE id = ?", document);
		return document;
	}

	String body(UUID invoiceId, String reasons, String payments) {
		return "{\"taxpayerId\":\"" + s.taxpayer() + "\",\"establishmentCode\":\"001\",\"emissionPointCode\":\"001\","
				+ "\"invoiceId\":\"" + invoiceId + "\",\"reasons\":" + reasons + ",\"payments\":" + payments + "}";
	}

	ResultActions debitNote(String body) throws Exception {
		return mvc.perform(json(post("/api/v1/debit-notes"), s.owner(), body).header("Idempotency-Key",
				UUID.randomUUID().toString()));
	}

	UUID issued(ResultActions result) throws Exception {
		String response = result.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(response, "$.id"));
	}

	void rejected(ResultActions result, int status, String errorCode) throws Exception {
		result.andExpect(status().is(status)).andExpect(jsonPath("$.errorCode").value(errorCode));
	}

	long notes() {
		return jdbc.queryForObject("SELECT count(*) FROM fiscal_documents WHERE tenant_id = ? AND document_type = '05'",
				Long.class, s.tenant());
	}

	String content(UUID document, String path) {
		return jdbc.queryForObject("SELECT content" + path + " FROM fiscal_documents WHERE id = ?", String.class,
				document);
	}

	@Test
	void debitNoteFromInvoice() throws Exception {
		UUID note = issued(debitNote(body(invoice, REASONS, PAYMENT_16_50)));

		Map<String, Object> invoiceRow = jdbc.queryForMap(
				"SELECT customer_id_number, customer_name FROM fiscal_documents WHERE id = ?", invoice);
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT document_type, state, customer_id, customer_id_number, customer_name, modified_document_id,
				 modified_doc_number, subtotal, total_tax, total FROM fiscal_documents WHERE id = ?
				""", note);
		assertThat(row).containsEntry("document_type", "05")
			.containsEntry("state", "RESERVED")
			.containsEntry("customer_id", s.customer())
			.containsEntry("customer_id_number", invoiceRow.get("customer_id_number"))
			.containsEntry("customer_name", invoiceRow.get("customer_name"))
			.containsEntry("modified_document_id", invoice)
			.containsEntry("modified_doc_number", "001-001-000000001");
		assertThat(row.get("subtotal").toString()).isEqualTo("15.00");
		assertThat(row.get("total_tax").toString()).isEqualTo("1.50");
		assertThat(row.get("total").toString()).isEqualTo("16.50");

		assertThat(content(note, "->'reasons'->0->>'reason'")).isEqualTo("Intereses por mora");
		assertThat(content(note, "->'reasons'->0->>'taxRateCode'")).isEqualTo("4");
		assertThat(content(note, "->'reasons'->1->>'reason'")).isEqualTo("Gastos de cobranza");
		assertThat(content(note, "->'reasons'->1->>'taxRateCode'")).isEqualTo("0");
		assertThat(content(note, "->'taxes'->0->>'rateCode'")).isEqualTo("0");
		assertThat(content(note, "->'taxes'->0->>'base'")).isEqualTo("5.00");
		assertThat(content(note, "->'taxes'->1->>'rateCode'")).isEqualTo("4");
		assertThat(content(note, "->'taxes'->1->>'amount'")).isEqualTo("1.50");
		assertThat(content(note, "->'payments'->0->>'methodCode'")).isEqualTo("20");
		assertThat(content(note, "->'payments'->0->>'amount'")).isEqualTo("16.50");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'DEBIT_NOTE_RESERVED' AND entity_id = ?",
				Integer.class, note))
			.isEqualTo(1);
	}

	@Test
	void paymentMustMatchTotal() throws Exception {
		rejected(debitNote(body(invoice, REASONS, "[{\"methodCode\":\"20\",\"amount\":\"16.49\"}]")), 422,
				"PAYMENTS_MISMATCH");
		assertThat(notes()).isZero();
	}

	@Test
	void unknownPaymentMethodIsRejected() throws Exception {
		rejected(debitNote(body(invoice, REASONS, "[{\"methodCode\":\"99\",\"amount\":\"16.50\"}]")), 422,
				"PAYMENT_METHOD_UNKNOWN");
		assertThat(notes()).isZero();
	}

	@Test
	void unknownTaxRateIsRejected() throws Exception {
		String reasons = "[{\"reason\":\"Intereses\",\"value\":\"10.00\",\"ivaRateCode\":\"99\"}]";
		rejected(debitNote(body(invoice, reasons, PAYMENT_16_50)), 422, "TAX_RATE_UNKNOWN");
		assertThat(notes()).isZero();
	}

	@Test
	void zeroValueIsRejected() throws Exception {
		String reasons = "[{\"reason\":\"Intereses\",\"value\":\"0.00\",\"ivaRateCode\":\"0\"}]";
		rejected(debitNote(body(invoice, reasons, "[{\"methodCode\":\"20\",\"amount\":\"0.00\"}]")), 422,
				"INVALID_LINE");
		assertThat(notes()).isZero();
	}

	@Test
	void manualDebitNote() throws Exception {
		String manual = "{\"taxpayerId\":\"" + s.taxpayer() + "\",\"establishmentCode\":\"001\","
				+ "\"emissionPointCode\":\"001\",\"modifiedDocument\":{\"code\":\"01\","
				+ "\"number\":\"001-002-000000123\",\"issueDate\":\"2024-01-15\"},\"customerId\":\"" + s.customer()
				+ "\",\"reasons\":" + REASONS + ",\"payments\":" + PAYMENT_16_50 + "}";

		UUID note = issued(debitNote(manual));

		Map<String, Object> row = jdbc.queryForMap("""
				SELECT modified_document_id, modified_doc_code, modified_doc_number, customer_id, total
				FROM fiscal_documents WHERE id = ?
				""", note);
		assertThat(row.get("modified_document_id")).isNull();
		assertThat(row).containsEntry("modified_doc_code", "01")
			.containsEntry("modified_doc_number", "001-002-000000123")
			.containsEntry("customer_id", s.customer());
		assertThat(row.get("total").toString()).isEqualTo("16.50");
	}

	@Test
	void modifiedDocumentIsRequiredOnce() throws Exception {
		String both = body(invoice, REASONS, PAYMENT_16_50).replaceFirst("\\{",
				"{\"customerId\":\"" + s.customer() + "\",");
		rejected(debitNote(both), 400, "MODIFIED_DOCUMENT_REQUIRED");
		rejected(debitNote(body(invoice, REASONS, PAYMENT_16_50).replace("\"invoiceId\":\"" + invoice + "\",", "")),
				400, "MODIFIED_DOCUMENT_REQUIRED");
		assertThat(notes()).isZero();
	}

	@Test
	void invoiceMustBeAuthorized() throws Exception {
		UUID reserved = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		rejected(debitNote(body(reserved, REASONS, PAYMENT_16_50)), 422, "MODIFIED_INVOICE_NOT_AUTHORIZED");
	}

	@Test
	void finalConsumerLimitApplies() throws Exception {
		UUID finalConsumer = createCustomer(s.owner(), "07", "9999999999999");
		UUID book = createProduct(s.owner(), "LIB-001", "50.00", "0");
		UUID invoiceToFinalConsumer = authorized(issueInvoice(s.owner(), s.invoiceJson(finalConsumer,
				"{\"productId\":\"" + book + "\",\"quantity\":\"1\"}", "50.00")));
		String reasons = "[{\"reason\":\"Intereses\",\"value\":\"50.01\",\"ivaRateCode\":\"0\"}]";

		rejected(debitNote(body(invoiceToFinalConsumer, reasons, "[{\"methodCode\":\"20\",\"amount\":\"50.01\"}]")),
				422, "FINAL_CONSUMER_LIMIT_EXCEEDED");

		// Hasta el límite sí se puede.
		issued(debitNote(body(invoiceToFinalConsumer,
				"[{\"reason\":\"Intereses\",\"value\":\"50.00\",\"ivaRateCode\":\"0\"}]",
				"[{\"methodCode\":\"20\",\"amount\":\"50.00\"}]")));
		assertThat(notes()).isEqualTo(1);
	}

	@Test
	void reasonsAndPaymentLimits() throws Exception {
		String one = "{\"reason\":\"Intereses\",\"value\":\"1.00\",\"ivaRateCode\":\"0\"}";
		String payment = "{\"methodCode\":\"20\",\"amount\":\"1.00\"}";

		rejected(debitNote(body(invoice, "[]", "[" + payment + "]")), 400, "VALIDATION_FAILED");
		rejected(debitNote(body(invoice, "[" + String.join(",", Collections.nCopies(21, one)) + "]",
				"[" + payment + "]")), 400, "VALIDATION_FAILED");
		rejected(debitNote(body(invoice, "[" + one + "]", "[]")), 400, "VALIDATION_FAILED");
		rejected(debitNote(body(invoice, "[" + one + "]", "[" + payment + "," + payment + "]")), 400,
				"VALIDATION_FAILED");
		rejected(debitNote(body(invoice, "[" + one.replace("Intereses", "Inte\\nreses") + "]", "[" + payment + "]")),
				400, "VALIDATION_FAILED");
		rejected(debitNote(body(invoice, "[" + one.replace("1.00", "1.004") + "]", "[" + payment + "]")), 400,
				"VALIDATION_FAILED");
		assertThat(notes()).isZero();

		// Veinte motivos sí caben.
		issued(debitNote(body(invoice, "[" + String.join(",", Collections.nCopies(20, one)) + "]",
				"[{\"methodCode\":\"20\",\"amount\":\"20.00\"}]")));
	}

}
