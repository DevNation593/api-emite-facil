package com.example.einvoice.notes;

import static com.example.einvoice.support.BillingScenario.creditLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestData;
import com.jayway.jsonpath.JsonPath;

/**
 * Nota de crédito por la API: sobre una factura autorizada de la plataforma (líneas y comprador copiados,
 * tope por línea y saldo de la factura) o sobre una factura escrita a mano, con líneas del catálogo.
 */
class CreditNoteApiIT extends AbstractIntegrationTest {

	BillingScenario s;

	/** Factura autorizada de 2 × 10.00 + IVA 15 % = 23.00. */
	UUID invoice;

	@BeforeEach
	void anAuthorizedInvoice() throws Exception {
		s = newBillingScenario();
		invoice = authorized(issueInvoice(s.owner(), s.invoiceJson("2", "23.00")));
	}

	UUID authorized(UUID document) {
		setState(document, "AUTHORIZED");
		return document;
	}

	void setState(UUID document, String state) {
		jdbc.update("UPDATE fiscal_documents SET state = ? WHERE id = ?", state, document);
	}

	ResultActions creditNote(String token, String key, String body) throws Exception {
		return mvc.perform(json(post("/api/v1/credit-notes"), token, body).header("Idempotency-Key", key));
	}

	ResultActions creditNote(String body) throws Exception {
		return creditNote(s.owner(), UUID.randomUUID().toString(), body);
	}

	UUID issued(ResultActions result) throws Exception {
		String body = result.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	void rejected(ResultActions result, int status, String errorCode) throws Exception {
		result.andExpect(status().is(status)).andExpect(jsonPath("$.errorCode").value(errorCode));
	}

	Map<String, Object> row(UUID document) {
		return jdbc.queryForMap("""
				SELECT document_type, state, customer_id, customer_id_number, customer_name, modified_document_id,
				 modified_doc_code, modified_doc_number, modified_doc_date, subtotal, total_tax, total,
				 content->>'reason' AS reason, content->'lines'->0->>'invoiceLineNo' AS invoice_line_no,
				 content->'lines'->0->>'mainCode' AS main_code, content->'lines'->0->>'description' AS description,
				 content->'lines'->0->>'taxRateCode' AS tax_rate_code, content->'lines'->0->>'quantity' AS quantity,
				 content->'lines'->0->>'productId' AS product_id
				FROM fiscal_documents WHERE id = ?
				""", document);
	}

	long notes() {
		return jdbc.queryForObject("SELECT count(*) FROM fiscal_documents WHERE tenant_id = ? AND document_type = '04'",
				Long.class, s.tenant());
	}

	static LocalDate todayInEcuador() {
		return LocalDate.now(ZoneId.of("America/Guayaquil"));
	}

	@Test
	void creditNoteFromInvoiceCopiesLinesAndBuyer() throws Exception {
		UUID note = issued(creditNote(s.creditNoteJson(invoice, creditLine(1, "1"))));

		Map<String, Object> invoiceRow = jdbc.queryForMap(
				"SELECT customer_id_number, customer_name, issue_date FROM fiscal_documents WHERE id = ?", invoice);
		Map<String, Object> noteRow = row(note);
		assertThat(noteRow).containsEntry("document_type", "04")
			.containsEntry("state", "RESERVED")
			.containsEntry("customer_id", s.customer())
			.containsEntry("customer_id_number", invoiceRow.get("customer_id_number"))
			.containsEntry("customer_name", invoiceRow.get("customer_name"))
			.containsEntry("modified_document_id", invoice)
			.containsEntry("modified_doc_code", "01")
			.containsEntry("modified_doc_number", "001-001-000000001")
			.containsEntry("modified_doc_date", invoiceRow.get("issue_date"))
			.containsEntry("reason", "Devolución de mercadería")
			.containsEntry("invoice_line_no", "1")
			.containsEntry("main_code", "SRV-001")
			.containsEntry("description", "Servicio de consultoría")
			.containsEntry("tax_rate_code", "4")
			.containsEntry("product_id", s.product().toString());
		assertThat(noteRow.get("total").toString()).isEqualTo("11.50");
		assertThat(noteRow.get("total_tax").toString()).isEqualTo("1.50");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'CREDIT_NOTE_RESERVED' AND entity_id = ?",
				Integer.class, note))
			.isEqualTo(1);
	}

	@Test
	void theBuyerIsTheOneFrozenInTheInvoice() throws Exception {
		Map<String, Object> original = jdbc.queryForMap("""
				SELECT customer_id_type, customer_id_number, customer_name, customer_address, customer_email
				FROM fiscal_documents WHERE id = ?
				""", invoice);
		// Después de la factura el cliente se edita y se archiva (borrado lógico, V17).
		jdbc.update("""
				UPDATE customers SET name = 'Otro Nombre', id_number = '0912345678', email = 'otro@example.test',
				 address = 'Otra dirección', deleted_at = now() WHERE id = ?
				""", s.customer());

		UUID note = issued(creditNote(s.creditNoteJson(invoice, creditLine(1, "1"))));

		Map<String, Object> noteBuyer = jdbc.queryForMap("""
				SELECT customer_id_type, customer_id_number, customer_name, customer_address, customer_email
				FROM fiscal_documents WHERE id = ?
				""", note);
		assertThat(noteBuyer).isEqualTo(original).containsEntry("customer_name", "María Pérez");
		assertThat(row(note)).containsEntry("customer_id", s.customer());
	}

	@Test
	void anOmittedDiscountIsProratedFromTheInvoiceLine() throws Exception {
		// 2 × 10.00 con 1.00 de descuento (19.00 + IVA = 21.85).
		String discountedLine = "{\"productId\":\"" + s.product() + "\",\"quantity\":\"2\",\"discount\":\"1.00\"}";

		// Devolver 1 sin descuento lleva la mitad: 0.50, y 9.50 + IVA 1.43 = 10.93.
		UUID half = issued(creditNote(s.creditNoteJson(
				authorized(issueInvoice(s.owner(), s.invoiceJson(s.customer(), discountedLine, "21.85"))),
				creditLine(1, "1"))));
		assertThat(lineDiscount(half)).isEqualTo("0.50");
		assertThat(row(half).get("total").toString()).isEqualTo("10.93");

		// Devolver toda la cantidad lleva el descuento original entero.
		UUID whole = issued(creditNote(s.creditNoteJson(
				authorized(issueInvoice(s.owner(), s.invoiceJson(s.customer(), discountedLine, "21.85"))),
				creditLine(1, "2"))));
		assertThat(lineDiscount(whole)).isEqualTo("1.00");
		assertThat(row(whole).get("total").toString()).isEqualTo("21.85");
	}

	@Test
	void theDiscountCannotGoBelowTheProratedOne() throws Exception {
		// 2 × 10.00 con 1.00 de descuento (19.00 + IVA = 21.85): por 1 unidad, el descuento mínimo es 0.50.
		String discountedLine = "{\"productId\":\"" + s.product() + "\",\"quantity\":\"2\",\"discount\":\"1.00\"}";
		UUID discounted = authorized(issueInvoice(s.owner(), s.invoiceJson(s.customer(), discountedLine, "21.85")));

		// Sin descuento, la línea acreditaría 10.00 contra los 9.50 facturados.
		rejected(creditNote(s.creditNoteJson(discounted,
				"{\"invoiceLineNo\":1,\"quantity\":\"1\",\"discount\":\"0\"}")), 422, "CREDIT_LINE_EXCEEDS_INVOICE");
		rejected(creditNote(s.creditNoteJson(discounted,
				"{\"invoiceLineNo\":1,\"quantity\":\"1\",\"discount\":\"0.49\"}")), 422, "CREDIT_LINE_EXCEEDS_INVOICE");
		assertThat(notes()).isZero();

		UUID prorated = issued(creditNote(s.creditNoteJson(discounted,
				"{\"invoiceLineNo\":1,\"quantity\":\"1\",\"discount\":\"0.50\"}")));
		assertThat(lineDiscount(prorated)).isEqualTo("0.50");
		UUID higher = issued(creditNote(s.creditNoteJson(discounted,
				"{\"invoiceLineNo\":1,\"quantity\":\"1\",\"discount\":\"0.75\"}")));
		assertThat(lineDiscount(higher)).isEqualTo("0.75");
	}

	String lineDiscount(UUID note) {
		return jdbc.queryForObject("SELECT content->'lines'->0->>'discount' FROM fiscal_documents WHERE id = ?",
				String.class, note);
	}

	@Test
	void lineAboveTheOriginalIsRejected() throws Exception {
		rejected(creditNote(s.creditNoteJson(invoice, creditLine(1, "3"))), 422, "CREDIT_LINE_EXCEEDS_INVOICE");
		rejected(creditNote(s.creditNoteJson(invoice, "{\"invoiceLineNo\":1,\"quantity\":\"1\",\"unitPrice\":\"10.01\"}")),
				422, "CREDIT_LINE_EXCEEDS_INVOICE");
		rejected(creditNote(s.creditNoteJson(invoice, creditLine(9, "1"))), 422, "CREDIT_LINE_UNKNOWN");

		// Una línea de la factura no se repite en la misma nota.
		rejected(creditNote(s.creditNoteJson(invoice, creditLine(1, "1") + "," + creditLine(1, "1"))), 400,
				"VALIDATION_FAILED");
		// Con factura, las líneas se toman de ella, no del catálogo.
		rejected(creditNote(s.creditNoteJson(invoice, "{\"productId\":\"" + s.product() + "\",\"quantity\":\"1\"}")),
				400, "VALIDATION_FAILED");
		assertThat(notes()).isZero();
	}

	@Test
	void creditCannotExceedTheInvoice() throws Exception {
		issued(creditNote(s.creditNoteJson(invoice, creditLine(1, "2"))));

		rejected(creditNote(s.creditNoteJson(invoice, "{\"invoiceLineNo\":1,\"quantity\":\"1\",\"unitPrice\":\"0.01\"}")),
				422, "CREDIT_EXCEEDS_INVOICE");
		assertThat(notes()).isEqualTo(1);
	}

	@Test
	void rejectedNotesDoNotConsumeBalance() throws Exception {
		UUID first = issued(creditNote(s.creditNoteJson(invoice, creditLine(1, "2"))));
		setState(first, "NOT_AUTHORIZED");

		issued(creditNote(s.creditNoteJson(invoice, creditLine(1, "2"))));
	}

	@Test
	void concurrentNotesNeverExceedTheInvoice() throws Exception {
		// Cada una por el 60 %: 1.2 × 10.00 + IVA = 13.80 de 23.00.
		String body = s.creditNoteJson(invoice, creditLine(1, "1.2"));
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<MvcResult> results = new ArrayList<>();
		try {
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				Callable<MvcResult> request = () -> {
					start.await();
					return creditNote(s.owner(), UUID.randomUUID().toString(), body).andReturn();
				};
				futures.add(pool.submit(request));
			}
			start.countDown();
			for (Future<MvcResult> future : futures) {
				results.add(future.get());
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(202, 422);
		MvcResult refused = results.stream().filter(result -> result.getResponse().getStatus() == 422).findFirst().orElseThrow();
		assertThat(JsonPath.<String>read(refused.getResponse().getContentAsString(), "$.errorCode"))
			.isEqualTo("CREDIT_EXCEEDS_INVOICE");
		assertThat(notes()).isEqualTo(1);
	}

	@Test
	void invoiceMustBeAuthorizedSameIssuerAndOwn() throws Exception {
		UUID reserved = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		rejected(creditNote(s.creditNoteJson(reserved, creditLine(1, "1"))), 422, "MODIFIED_INVOICE_NOT_AUTHORIZED");

		UUID otherIssuer = createTaxpayer(s.owner(), TestData.ruc());
		createEmissionPoint(s.owner(), createEstablishment(s.owner(), otherIssuer, "001"), "001");
		UUID otherIssuerInvoice = authorized(issueInvoice(s.owner(),
				s.invoiceJson("2", "23.00").replace(s.taxpayer().toString(), otherIssuer.toString())));
		rejected(creditNote(s.creditNoteJson(otherIssuerInvoice, creditLine(1, "1"))), 422,
				"MODIFIED_INVOICE_OTHER_ISSUER");

		BillingScenario other = newBillingScenario();
		creditNote(other.owner(), UUID.randomUUID().toString(), other.creditNoteJson(invoice, creditLine(1, "1")))
			.andExpect(status().isNotFound());

		UUID note = authorized(issued(creditNote(s.creditNoteJson(invoice, creditLine(1, "1")))));
		rejected(creditNote(s.creditNoteJson(note, creditLine(1, "1"))), 422, "MODIFIED_DOCUMENT_NOT_INVOICE");
	}

	@Test
	void manualCreditNoteUsesCatalogLines() throws Exception {
		UUID note = issued(creditNote(s.manualCreditNoteJson("001-002-000000123", "2024-01-15", "1")));

		Map<String, Object> noteRow = row(note);
		assertThat(noteRow.get("modified_document_id")).isNull();
		assertThat(noteRow).containsEntry("modified_doc_code", "01")
			.containsEntry("modified_doc_number", "001-002-000000123")
			.containsEntry("modified_doc_date", java.sql.Date.valueOf("2024-01-15"))
			.containsEntry("customer_id", s.customer())
			.containsEntry("main_code", "SRV-001")
			.containsEntry("product_id", s.product().toString());
		assertThat(noteRow.get("invoice_line_no")).isNull();
		assertThat(noteRow.get("total").toString()).isEqualTo("11.50");
	}

	@Test
	void modifiedDocumentIsRequiredOnce() throws Exception {
		String manual = s.manualCreditNoteJson("001-002-000000123", "2024-01-15", "1");
		String withoutAny = manual.replaceFirst(",\"modifiedDocument\":\\{[^}]*\\},\"customerId\":\"[^\"]*\"", "");
		rejected(creditNote(withoutAny), 400, "MODIFIED_DOCUMENT_REQUIRED");

		String both = manual.replaceFirst("\\{", "{\"invoiceId\":\"" + invoice + "\",");
		rejected(creditNote(both), 400, "MODIFIED_DOCUMENT_REQUIRED");

		String invoiceAndCustomer = s.creditNoteJson(invoice, creditLine(1, "1"))
			.replaceFirst("\\{", "{\"customerId\":\"" + s.customer() + "\",");
		rejected(creditNote(invoiceAndCustomer), 400, "MODIFIED_DOCUMENT_REQUIRED");

		String withoutCustomer = manual.replaceFirst(",\"customerId\":\"[^\"]*\"", "");
		rejected(creditNote(withoutCustomer), 400, "MODIFIED_DOCUMENT_REQUIRED");
		assertThat(notes()).isZero();
	}

	@Test
	void modifiedDocumentCannotBeLater() throws Exception {
		String tomorrow = todayInEcuador().plusDays(1).toString();
		rejected(creditNote(s.manualCreditNoteJson("001-002-000000123", tomorrow, "1")), 422,
				"INVALID_MODIFIED_DOCUMENT_DATE");

		jdbc.update("UPDATE fiscal_documents SET issue_date = ? WHERE id = ?", java.sql.Date.valueOf(tomorrow), invoice);
		rejected(creditNote(s.creditNoteJson(invoice, creditLine(1, "1"))), 422, "INVALID_MODIFIED_DOCUMENT_DATE");
		assertThat(notes()).isZero();
	}

	@Test
	void idempotentRetryReturnsTheSameNote() throws Exception {
		String key = UUID.randomUUID().toString();
		String body = s.creditNoteJson(invoice, creditLine(1, "1"));
		UUID first = issued(creditNote(s.owner(), key, body));

		assertThat(issued(creditNote(s.owner(), key, body))).isEqualTo(first);
		creditNote(s.owner(), key, s.creditNoteJson(invoice, creditLine(1, "2"))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
		mvc.perform(json(post("/api/v1/credit-notes"), s.owner(), body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
		assertThat(notes()).isEqualTo(1);
	}

	@Test
	void invalidLineAmountsUseTheInvoiceRules() throws Exception {
		rejected(creditNote(s.creditNoteJson(invoice, "{\"invoiceLineNo\":1,\"quantity\":\"1\",\"discount\":\"10.01\"}")),
				422, "INVALID_LINE");
		rejected(creditNote(s.creditNoteJson(invoice, creditLine(1, "0"))), 422, "INVALID_LINE");
		assertThat(notes()).isZero();
	}

	@Test
	void reasonIsRequiredAndSingleLine() throws Exception {
		String body = s.creditNoteJson(invoice, creditLine(1, "1"));
		rejected(creditNote(body.replace("Devolución de mercadería", "")), 400, "VALIDATION_FAILED");
		rejected(creditNote(body.replace("Devolución de mercadería", "Devolución\\nparcial")), 400, "VALIDATION_FAILED");
		rejected(creditNote(body.replace("Devolución de mercadería", "x".repeat(301))), 400, "VALIDATION_FAILED");
		assertThat(notes()).isZero();
	}

}
