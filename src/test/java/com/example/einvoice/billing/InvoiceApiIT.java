package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.einvoice.sequence.AccessKey;
import com.example.einvoice.sequence.FiscalClock;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.jayway.jsonpath.JsonPath;

class InvoiceApiIT extends AbstractIntegrationTest {

	static final String DOCUMENTS = "/api/v1/documents/";

	BillingScenario s;

	@BeforeEach
	void scenario() throws Exception {
		s = newBillingScenario();
	}

	static LocalDate today() {
		return FiscalClock.dateOf(Instant.now());
	}

	String line(UUID productId, String quantity, String unitPrice, String discount) {
		return "{\"productId\":\"" + productId + "\",\"quantity\":\"" + quantity + "\""
				+ (unitPrice == null ? "" : ",\"unitPrice\":\"" + unitPrice + "\"")
				+ (discount == null ? "" : ",\"discount\":\"" + discount + "\"") + "}";
	}

	long documentCount() {
		return jdbc.queryForObject("SELECT count(*) FROM fiscal_documents WHERE tenant_id = ?", Long.class, s.tenant());
	}

	Long nextValue() {
		return jdbc.query("SELECT next_value FROM document_sequences WHERE emission_point_id = ?",
				rs -> rs.next() ? rs.getLong(1) : null, s.point());
	}

	@Test
	void billerIssuesInvoice() throws Exception {
		String biller = memberToken(s.tenant(), "BILLER");
		String response = mvc.perform(invoiceRequest(biller, s.invoiceJson("2", "23.00")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.state").value("RESERVED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonPath.read(response, "$.id");
		assertThat(JsonPath.<String>read(response, "$.statusUrl")).isEqualTo(DOCUMENTS + id);

		String document = mvc.perform(as(get(DOCUMENTS + id), biller))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documentType").value("01"))
			.andExpect(jsonPath("$.state").value("RESERVED"))
			.andExpect(jsonPath("$.environment").value("1"))
			.andExpect(jsonPath("$.taxpayerId").value(s.taxpayer().toString()))
			.andExpect(jsonPath("$.number").value("001-001-000000001"))
			.andExpect(jsonPath("$.sequence").value("000000001"))
			.andExpect(jsonPath("$.issueDate").value(today().toString()))
			.andExpect(jsonPath("$.currency").value("USD"))
			.andExpect(jsonPath("$.customer.idType").value("05"))
			.andExpect(jsonPath("$.customer.idNumber").value("1712345678"))
			.andExpect(jsonPath("$.customer.name").value("María Pérez"))
			.andExpect(jsonPath("$.subtotal").value("20.00"))
			.andExpect(jsonPath("$.totalDiscount").value("0.00"))
			.andExpect(jsonPath("$.totalTax").value("3.00"))
			.andExpect(jsonPath("$.total").value("23.00"))
			.andExpect(jsonPath("$.taxes", hasSize(1)))
			.andExpect(jsonPath("$.taxes[0].taxCode").value("2"))
			.andExpect(jsonPath("$.taxes[0].rateCode").value("4"))
			.andExpect(jsonPath("$.taxes[0].rate").value("15.00"))
			.andExpect(jsonPath("$.taxes[0].base").value("20.00"))
			.andExpect(jsonPath("$.taxes[0].amount").value("3.00"))
			.andExpect(jsonPath("$.lines", hasSize(1)))
			.andExpect(jsonPath("$.lines[0].mainCode").value("SRV-001"))
			.andExpect(jsonPath("$.lines[0].description").value("Servicio de consultoría"))
			.andExpect(jsonPath("$.lines[0].quantity").value("2.000000"))
			.andExpect(jsonPath("$.lines[0].unitPrice").value("10.000000"))
			.andExpect(jsonPath("$.lines[0].discount").value("0.00"))
			.andExpect(jsonPath("$.lines[0].subtotal").value("20.00"))
			.andExpect(jsonPath("$.lines[0].taxAmount").value("3.00"))
			.andExpect(jsonPath("$.payments[0].methodCode").value("20"))
			.andExpect(jsonPath("$.payments[0].amount").value("23.00"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		String accessKey = JsonPath.read(document, "$.accessKey");
		assertThat(AccessKey.valid(accessKey)).isTrue();
		assertThat(accessKey).startsWith(
				today().format(DateTimeFormatter.ofPattern("ddMMyyyy")) + "01" + s.ruc() + "1" + "001001" + "000000001");
		assertThat(accessKey.charAt(47)).isEqualTo('1');
	}

	@Test
	void secondInvoiceGetsNextSequence() throws Exception {
		UUID first = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		UUID second = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		assertThat(jdbc.queryForList("SELECT sequence FROM fiscal_documents WHERE id IN (?, ?) ORDER BY sequence",
				Integer.class, first, second))
			.containsExactly(1, 2);
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT access_key) FROM fiscal_documents WHERE id IN (?, ?)",
				Integer.class, first, second))
			.isEqualTo(2);
		assertThat(nextValue()).isEqualTo(3L);
	}

	@Test
	void writesStateEventAuditAndOutbox() throws Exception {
		UUID id = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		assertThat(jdbc.queryForMap("SELECT previous_state, new_state FROM state_events WHERE document_id = ?", id))
			.containsEntry("previous_state", null)
			.containsEntry("new_state", "RESERVED");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'INVOICE_RESERVED' AND entity_id = ? AND tenant_id = ?",
				Integer.class, id, s.tenant()))
			.isEqualTo(1);
		var outbox = jdbc.queryForMap(
				"SELECT event_type, payload::text AS payload, dispatched_at FROM outbox_events WHERE aggregate_id = ?",
				id);
		assertThat(outbox.get("event_type")).isEqualTo("DOCUMENT_RESERVED");
		assertThat(outbox.get("dispatched_at")).isNull();
		assertThat(JsonPath.<java.util.Map<String, Object>>read((String) outbox.get("payload"), "$"))
			.containsOnlyKeys("documentId")
			.containsEntry("documentId", id.toString());
	}

	@Test
	void auditorCannotIssue() throws Exception {
		mvc.perform(invoiceRequest(memberToken(s.tenant(), "AUDITOR"), s.invoiceJson("1", "11.50")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
		assertThat(documentCount()).isZero();
	}

	@Test
	void requiresIdempotencyKey() throws Exception {
		mvc.perform(json(post("/api/v1/invoices"), s.owner(), s.invoiceJson("1", "11.50")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
		for (String invalid : new String[] { "con espacios", "x".repeat(101), "ñ" }) {
			mvc.perform(json(post("/api/v1/invoices"), s.owner(), s.invoiceJson("1", "11.50"))
				.header("Idempotency-Key", invalid))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_INVALID"));
		}
		assertThat(documentCount()).isZero();
	}

	@Test
	void lineCanOverridePriceAndDiscount() throws Exception {
		// 3 x 8.50 - 5.50 = 20.00; IVA 15 % = 3.00
		UUID id = issueInvoice(s.owner(),
				s.invoiceJson(s.customer(), line(s.product(), "3", "8.50", "5.50"), "23.00"));
		mvc.perform(as(get(DOCUMENTS + id), s.owner()))
			.andExpect(jsonPath("$.lines[0].unitPrice").value("8.500000"))
			.andExpect(jsonPath("$.lines[0].discount").value("5.50"))
			.andExpect(jsonPath("$.subtotal").value("20.00"))
			.andExpect(jsonPath("$.totalDiscount").value("5.50"))
			.andExpect(jsonPath("$.total").value("23.00"));
	}

	@Test
	void mixedRatesProduceOneTaxTotalPerRate() throws Exception {
		UUID exempt = createProduct(s.owner(), "LIB-001", "7.00", "0");
		String lines = line(s.product(), "1", null, null) + "," + line(exempt, "2", null, null);
		UUID id = issueInvoice(s.owner(), s.invoiceJson(s.customer(), lines, "25.50"));
		mvc.perform(as(get(DOCUMENTS + id), s.owner()))
			.andExpect(jsonPath("$.taxes", hasSize(2)))
			.andExpect(jsonPath("$.taxes[0].rateCode").value("0"))
			.andExpect(jsonPath("$.taxes[0].base").value("14.00"))
			.andExpect(jsonPath("$.taxes[1].rateCode").value("4"))
			.andExpect(jsonPath("$.taxes[1].amount").value("1.50"))
			.andExpect(jsonPath("$.lines", hasSize(2)))
			.andExpect(jsonPath("$.lines[1].mainCode").value("LIB-001"))
			.andExpect(jsonPath("$.total").value("25.50"));
	}

	@Test
	void snapshotSurvivesLaterChanges() throws Exception {
		UUID id = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		jdbc.update("UPDATE customers SET name = 'Otro Nombre', id_number = '0912345678' WHERE id = ?", s.customer());
		jdbc.update("UPDATE products SET name = 'Otro producto', unit_price = 99, iva_rate_code = '0' WHERE id = ?",
				s.product());
		mvc.perform(as(get(DOCUMENTS + id), s.owner()))
			.andExpect(jsonPath("$.customer.name").value("María Pérez"))
			.andExpect(jsonPath("$.customer.idNumber").value("1712345678"))
			.andExpect(jsonPath("$.lines[0].description").value("Servicio de consultoría"))
			.andExpect(jsonPath("$.lines[0].unitPrice").value("10.000000"))
			.andExpect(jsonPath("$.total").value("11.50"));
	}

	@Test
	void rejectsFutureIssueDate() throws Exception {
		String body = s.invoiceJson("1", "11.50").replace("\"lines\"",
				"\"issueDate\":\"" + today().plusDays(1) + "\",\"lines\"");
		mvc.perform(invoiceRequest(s.owner(), body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("INVALID_ISSUE_DATE"));
		assertThat(documentCount()).isZero();
	}

	@Test
	void rejectsIssueDateBeforeLastDocument() throws Exception {
		issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		String yesterday = s.invoiceJson("1", "11.50").replace("\"lines\"",
				"\"issueDate\":\"" + today().minusDays(1) + "\",\"lines\"");
		mvc.perform(invoiceRequest(s.owner(), yesterday))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("INVALID_ISSUE_DATE"));
		assertThat(documentCount()).isEqualTo(1);
		assertThat(nextValue()).isEqualTo(2L);
	}

	@Test
	void backdatedInvoiceIsAllowedOnAnUnusedPoint() throws Exception {
		String yesterday = s.invoiceJson("1", "11.50").replace("\"lines\"",
				"\"issueDate\":\"" + today().minusDays(1) + "\",\"lines\"");
		UUID id = issueInvoice(s.owner(), yesterday);
		mvc.perform(as(get(DOCUMENTS + id), s.owner()))
			.andExpect(jsonPath("$.issueDate").value(today().minusDays(1).toString()));
	}

	@Test
	void rejectsUnsupportedCurrency() throws Exception {
		String body = s.invoiceJson("1", "11.50").replace("\"lines\"", "\"currency\":\"EUR\",\"lines\"");
		mvc.perform(invoiceRequest(s.owner(), body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_CURRENCY"));
	}

	@Test
	void rejectsUnknownPaymentMethod() throws Exception {
		String body = s.invoiceJson("1", "11.50").replace("\"methodCode\":\"20\"", "\"methodCode\":\"99\"");
		mvc.perform(invoiceRequest(s.owner(), body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("PAYMENT_METHOD_UNKNOWN"));
	}

	@Test
	void rejectsPaymentsThatDoNotAddUpWithoutBurningASequence() throws Exception {
		issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		for (String amount : new String[] { "11.49", "11.51", "100" }) {
			mvc.perform(invoiceRequest(s.owner(), s.invoiceJson("1", amount)))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errorCode").value("PAYMENTS_MISMATCH"));
		}
		assertThat(documentCount()).isEqualTo(1);
		assertThat(nextValue()).isEqualTo(2L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ?", Long.class, s.tenant()))
			.isEqualTo(1);
	}

	@Test
	void paymentsCanBeSplit() throws Exception {
		String body = s.invoiceJson("2", "20.00").replace("}]}",
				"},{\"methodCode\":\"01\",\"amount\":\"3.00\"}]}");
		UUID id = issueInvoice(s.owner(), body);
		mvc.perform(as(get(DOCUMENTS + id), s.owner()))
			.andExpect(jsonPath("$.payments", hasSize(2)))
			.andExpect(jsonPath("$.payments[1].methodCode").value("01"))
			.andExpect(jsonPath("$.payments[1].amount").value("3.00"));
	}

	@Test
	void finalConsumerAboveFiftyIsRejected() throws Exception {
		UUID finalConsumer = createCustomer(s.owner(), "07", "9999999999999");
		UUID book = createProduct(s.owner(), "LIB-001", "50.00", "0");
		issueInvoice(s.owner(), s.invoiceJson(finalConsumer, line(book, "1", null, null), "50.00"));
		mvc.perform(invoiceRequest(s.owner(),
				s.invoiceJson(finalConsumer, line(book, "1", "50.01", null), "50.01")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("FINAL_CONSUMER_LIMIT_EXCEEDED"));
		// El mismo importe a un cliente identificado sí se puede facturar.
		issueInvoice(s.owner(), s.invoiceJson(s.customer(), line(book, "1", "50.01", null), "50.01"));
	}

	@Test
	void rejectsInvalidLines() throws Exception {
		mvc.perform(invoiceRequest(s.owner(), s.invoiceJson("0", "0")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("INVALID_LINE"));
		mvc.perform(invoiceRequest(s.owner(),
				s.invoiceJson(s.customer(), line(s.product(), "1", null, "10.01"), "0")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("INVALID_LINE"));
		mvc.perform(invoiceRequest(s.owner(), s.invoiceJson(s.customer(), "", "0")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		StringBuilder many = new StringBuilder();
		for (int i = 0; i < 501; i++) {
			many.append(i == 0 ? "" : ",").append(line(s.product(), "1", null, null));
		}
		mvc.perform(invoiceRequest(s.owner(), s.invoiceJson(s.customer(), many.toString(), "5761.50")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		for (String quantity : new String[] { "-1", "1.1234567", "1e2", "" }) {
			mvc.perform(invoiceRequest(s.owner(), s.invoiceJson(quantity, "11.50")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}
		assertThat(documentCount()).isZero();
	}

	@Test
	void rejectsNullElementsInLinesAndPayments() throws Exception {
		String valid = s.invoiceJson("1", "11.50");
		String nullLine = s.invoiceJson(s.customer(), "null", "11.50");
		String nullPayment = valid.substring(0, valid.indexOf("\"payments\":")) + "\"payments\":[null]}";
		for (String body : new String[] { nullLine, nullPayment }) {
			mvc.perform(invoiceRequest(s.owner(), body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		}
		assertThat(documentCount()).isZero();
	}

	@Test
	void rejectsAmountsBeyondWhatADocumentCanStore() throws Exception {
		String huge = line(s.product(), "2", "999999999999", null);
		mvc.perform(invoiceRequest(s.owner(), s.invoiceJson(s.customer(), huge, "999999999999")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("AMOUNT_TOO_LARGE"));
		String discounted = line(s.product(), "1", "999999999999", "999999999999.00");
		mvc.perform(invoiceRequest(s.owner(), s.invoiceJson(s.customer(), discounted + "," + discounted, "0")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("AMOUNT_TOO_LARGE"));
		assertThat(documentCount()).isZero();
		assertThat(nextValue()).isNull();
	}

	@Test
	void rejectsZeroPaymentBesideARealOne() throws Exception {
		String body = s.invoiceJson("1", "11.50").replace("}]}", "},{\"methodCode\":\"01\",\"amount\":\"0\"}]}");
		mvc.perform(invoiceRequest(s.owner(), body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("INVALID_PAYMENT"));
		assertThat(documentCount()).isZero();
	}

	@Test
	void fullyDiscountedInvoiceTotalsZero() throws Exception {
		UUID id = issueInvoice(s.owner(), s.invoiceJson(s.customer(), line(s.product(), "1", null, "10.00"), "0"));
		mvc.perform(as(get(DOCUMENTS + id), s.owner()))
			.andExpect(jsonPath("$.total").value("0.00"))
			.andExpect(jsonPath("$.payments[0].amount").value("0.00"));
	}

	@Test
	void unknownReferencesAreNotFound() throws Exception {
		String valid = s.invoiceJson("1", "11.50");
		String random = UUID.randomUUID().toString();
		for (String body : new String[] { valid.replace(s.taxpayer().toString(), random),
				valid.replace("\"establishmentCode\":\"001\"", "\"establishmentCode\":\"009\""),
				valid.replace("\"emissionPointCode\":\"001\"", "\"emissionPointCode\":\"009\""),
				valid.replace(s.customer().toString(), random), valid.replace(s.product().toString(), random) }) {
			mvc.perform(invoiceRequest(s.owner(), body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
		}
		assertThat(documentCount()).isZero();
		assertThat(nextValue()).isNull();
	}

	@Test
	void disabledEmissionPointIsRejected() throws Exception {
		jdbc.update("UPDATE emission_points SET enabled = false WHERE id = ?", s.point());
		mvc.perform(invoiceRequest(s.owner(), s.invoiceJson("1", "11.50")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.errorCode").value("EMISSION_POINT_DISABLED"));
	}

	@Test
	void listsDocumentsOfTheTenant() throws Exception {
		issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		UUID last = issueInvoice(s.owner(), s.invoiceJson("3", "34.50"));
		mvc.perform(as(get("/api/v1/documents").param("size", "2"), s.owner()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[0].id").value(last.toString()))
			.andExpect(jsonPath("$.items[0].number").value("001-001-000000003"))
			.andExpect(jsonPath("$.items[0].total").value("34.50"))
			.andExpect(jsonPath("$.items[0].state").value("RESERVED"))
			.andExpect(jsonPath("$.items[0].lines").doesNotExist());
		mvc.perform(as(get(DOCUMENTS + UUID.randomUUID()), s.owner())).andExpect(status().isNotFound());
	}

}
