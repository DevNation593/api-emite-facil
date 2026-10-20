package com.example.einvoice.notes;

import static com.example.einvoice.support.BillingScenario.creditLine;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.jayway.jsonpath.JsonPath;

/** La vista del comprobante (GET /documents/{id}) para las notas y para la factura acreditada. */
class NotesViewIT extends AbstractIntegrationTest {

	static final String REASONS = "[{\"reason\":\"Intereses por mora\",\"value\":\"10.00\",\"ivaRateCode\":\"4\"},"
			+ "{\"reason\":\"Gastos de cobranza\",\"value\":\"5.00\",\"ivaRateCode\":\"0\"}]";

	UUID issue(BillingScenario s, String path, String body) throws Exception {
		String response = mvc
			.perform(json(post(path), s.owner(), body).header("Idempotency-Key", UUID.randomUUID().toString()))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(response, "$.id"));
	}

	String debitNoteJson(BillingScenario s, UUID invoice) {
		return "{\"taxpayerId\":\"" + s.taxpayer() + "\",\"establishmentCode\":\"001\",\"emissionPointCode\":\"001\","
				+ "\"invoiceId\":\"" + invoice + "\",\"reasons\":" + REASONS
				+ ",\"payments\":[{\"methodCode\":\"20\",\"amount\":\"16.50\"}]}";
	}

	@Test
	void creditNoteViewShowsModifiedDocumentReasonAndLines() throws Exception {
		BillingScenario s = newBillingScenario();
		UUID invoice = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		jdbc.update("UPDATE fiscal_documents SET state = 'AUTHORIZED' WHERE id = ?", invoice);
		UUID note = issue(s, "/api/v1/credit-notes", s.creditNoteJson(invoice, creditLine(1, "1")));

		mvc.perform(as(get("/api/v1/documents/" + note), s.owner()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documentType").value("04"))
			.andExpect(jsonPath("$.modifiedDocument.code").value("01"))
			.andExpect(jsonPath("$.modifiedDocument.number").value("001-001-000000001"))
			.andExpect(jsonPath("$.modifiedDocument.documentId").value(invoice.toString()))
			.andExpect(jsonPath("$.modifiedDocument.issueDate").exists())
			.andExpect(jsonPath("$.reason").value("Devolución de mercadería"))
			.andExpect(jsonPath("$.reasons").doesNotExist())
			.andExpect(jsonPath("$.creditedTotal").doesNotExist())
			.andExpect(jsonPath("$.lines.length()").value(1))
			.andExpect(jsonPath("$.lines[0].invoiceLineNo").value(1))
			.andExpect(jsonPath("$.lines[0].mainCode").value("SRV-001"))
			.andExpect(jsonPath("$.lines[0].quantity").value("1.000000"))
			.andExpect(jsonPath("$.lines[0].taxRateCode").value("4"))
			.andExpect(jsonPath("$.taxes.length()").value(1))
			.andExpect(jsonPath("$.taxes[0].amount").value("1.50"))
			.andExpect(jsonPath("$.total").value("11.50"));
	}

	@Test
	void debitNoteViewShowsReasonsAndPayments() throws Exception {
		BillingScenario s = newBillingScenario();
		UUID invoice = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		jdbc.update("UPDATE fiscal_documents SET state = 'AUTHORIZED' WHERE id = ?", invoice);
		UUID note = issue(s, "/api/v1/debit-notes", debitNoteJson(s, invoice));

		mvc.perform(as(get("/api/v1/documents/" + note), s.owner()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documentType").value("05"))
			.andExpect(jsonPath("$.modifiedDocument.documentId").value(invoice.toString()))
			.andExpect(jsonPath("$.reason").doesNotExist())
			.andExpect(jsonPath("$.lines.length()").value(0))
			.andExpect(jsonPath("$.reasons.length()").value(2))
			.andExpect(jsonPath("$.reasons[0].reason").value("Intereses por mora"))
			.andExpect(jsonPath("$.reasons[0].value").value("10.00"))
			.andExpect(jsonPath("$.reasons[0].taxRateCode").value("4"))
			.andExpect(jsonPath("$.reasons[0].taxRate").value("15.00"))
			.andExpect(jsonPath("$.reasons[0].taxAmount").value("1.50"))
			.andExpect(jsonPath("$.reasons[1].taxAmount").value("0.00"))
			.andExpect(jsonPath("$.payments.length()").value(1))
			.andExpect(jsonPath("$.payments[0].methodCode").value("20"))
			.andExpect(jsonPath("$.payments[0].amount").value("16.50"))
			.andExpect(jsonPath("$.taxes.length()").value(2))
			.andExpect(jsonPath("$.total").value("16.50"));
	}

	@Test
	void invoiceViewShowsCreditedTotal() throws Exception {
		BillingScenario s = newBillingScenario();
		UUID invoice = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		jdbc.update("UPDATE fiscal_documents SET state = 'AUTHORIZED' WHERE id = ?", invoice);

		mvc.perform(as(get("/api/v1/documents/" + invoice), s.owner()))
			.andExpect(jsonPath("$.creditedTotal").value("0.00"))
			.andExpect(jsonPath("$.modifiedDocument").doesNotExist())
			.andExpect(jsonPath("$.reason").doesNotExist())
			.andExpect(jsonPath("$.lines[0].invoiceLineNo").doesNotExist());

		issue(s, "/api/v1/credit-notes", s.creditNoteJson(invoice, creditLine(1, "1")));

		mvc.perform(as(get("/api/v1/documents/" + invoice), s.owner()))
			.andExpect(jsonPath("$.creditedTotal").value("11.50"));
	}

}
