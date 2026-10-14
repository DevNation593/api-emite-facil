package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.einvoice.delivery.RideData;
import com.example.einvoice.xml.InvoiceXmlData;

/** Lectura y escritura de documentos fiscales. Toda lectura filtra por tenant. */
@Repository
public class FiscalDocumentRepository {

	public record NewDocument(UUID tenantId, UUID taxpayerId, UUID emissionPointId, UUID customerId,
			String documentType, String environment, String issuerRuc, String establishmentCode,
			String emissionPointCode, int sequence, String accessKey, String state, LocalDate issueDate,
			String currency, String customerIdType, String customerIdNumber, String customerName,
			String customerAddress, String customerEmail, InvoiceTotals totals, UUID createdBy,
			String issuerLegalName, String issuerTradeName, String issuerHeadOfficeAddress,
			String establishmentAddress, boolean issuerAccountingRequired, String issuerSpecialTaxpayerCode,
			String issuerWithholdingAgentCode, String issuerRimpeRegime, String providerRuc) {
	}

	public record NewLine(UUID productId, String mainCode, String description, BigDecimal quantity,
			BigDecimal unitPrice, BigDecimal discount, BigDecimal subtotal, String taxCode, String taxRateCode,
			BigDecimal taxRate, BigDecimal taxAmount) {
	}

	public record NewPayment(String methodCode, BigDecimal amount) {
	}

	public record DocumentRow(UUID id, UUID taxpayerId, String documentType, String state, String environment,
			String establishmentCode, String emissionPointCode, int sequence, String accessKey, LocalDate issueDate,
			String currency, String customerIdType, String customerIdNumber, String customerName, BigDecimal subtotal,
			BigDecimal totalDiscount, BigDecimal totalTax, BigDecimal total, String authorizationNumber,
			OffsetDateTime authorizedAt) {
	}

	public record LineRow(int lineNo, String mainCode, String description, BigDecimal quantity, BigDecimal unitPrice,
			BigDecimal discount, BigDecimal subtotal, String taxCode, String taxRateCode, BigDecimal taxRate,
			BigDecimal taxAmount, UUID productId) {
	}

	/** A quién se emitió y con qué otros comprobantes está enlazado por una reemisión. */
	public record Links(UUID customerId, UUID replacesDocumentId, UUID replacedByDocumentId) {
	}

	public record PaymentRow(int lineNo, String methodCode, BigDecimal amount) {
	}

	private static final String DOCUMENT_COLUMNS = """
			id, taxpayer_id, document_type, state, environment, establishment_code, emission_point_code, sequence,
			access_key, issue_date, currency, customer_id_type, customer_id_number, customer_name, subtotal,
			total_discount, total_tax, total, authorization_number, authorized_at
			""";

	private final JdbcClient jdbc;

	public FiscalDocumentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public UUID insert(NewDocument document, List<NewLine> lines, List<NewPayment> payments) {
		UUID id = jdbc.sql("""
				INSERT INTO fiscal_documents (tenant_id, taxpayer_id, emission_point_id, customer_id, document_type,
				 environment, issuer_ruc, establishment_code, emission_point_code, sequence, access_key, state,
				 issue_date, currency, customer_id_type, customer_id_number, customer_name, customer_address,
				 customer_email, subtotal, total_discount, total_tax, total, created_by, issuer_legal_name,
				 issuer_trade_name, issuer_head_office_address, establishment_address, issuer_accounting_required,
				 issuer_special_taxpayer_code, issuer_withholding_agent_code, issuer_rimpe_regime, provider_ruc)
				VALUES (:tenantId, :taxpayerId, :emissionPointId, :customerId, :documentType, :environment,
				 :issuerRuc, :establishmentCode, :emissionPointCode, :sequence, :accessKey, :state, :issueDate,
				 :currency, :customerIdType, :customerIdNumber, :customerName, :customerAddress, :customerEmail,
				 :subtotal, :totalDiscount, :totalTax, :total, :createdBy, :issuerLegalName, :issuerTradeName,
				 :issuerHeadOfficeAddress, :establishmentAddress, :issuerAccountingRequired,
				 :issuerSpecialTaxpayerCode, :issuerWithholdingAgentCode, :issuerRimpeRegime, :providerRuc)
				RETURNING id
				""")
			.param("tenantId", document.tenantId())
			.param("taxpayerId", document.taxpayerId())
			.param("emissionPointId", document.emissionPointId())
			.param("customerId", document.customerId())
			.param("documentType", document.documentType())
			.param("environment", document.environment())
			.param("issuerRuc", document.issuerRuc())
			.param("establishmentCode", document.establishmentCode())
			.param("emissionPointCode", document.emissionPointCode())
			.param("sequence", document.sequence())
			.param("accessKey", document.accessKey())
			.param("state", document.state())
			.param("issueDate", document.issueDate())
			.param("currency", document.currency())
			.param("customerIdType", document.customerIdType())
			.param("customerIdNumber", document.customerIdNumber())
			.param("customerName", document.customerName())
			.param("customerAddress", document.customerAddress())
			.param("customerEmail", document.customerEmail())
			.param("subtotal", document.totals().subtotal())
			.param("totalDiscount", document.totals().totalDiscount())
			.param("totalTax", document.totals().totalTax())
			.param("total", document.totals().total())
			.param("createdBy", document.createdBy())
			.param("issuerLegalName", document.issuerLegalName())
			.param("issuerTradeName", document.issuerTradeName())
			.param("issuerHeadOfficeAddress", document.issuerHeadOfficeAddress())
			.param("establishmentAddress", document.establishmentAddress())
			.param("issuerAccountingRequired", document.issuerAccountingRequired())
			.param("issuerSpecialTaxpayerCode", document.issuerSpecialTaxpayerCode())
			.param("issuerWithholdingAgentCode", document.issuerWithholdingAgentCode())
			.param("issuerRimpeRegime", document.issuerRimpeRegime())
			.param("providerRuc", document.providerRuc())
			.query(UUID.class)
			.single();
		UUID tenantId = document.tenantId();
		for (int i = 0; i < lines.size(); i++) {
			NewLine line = lines.get(i);
			jdbc.sql("""
					INSERT INTO fiscal_document_lines (tenant_id, document_id, line_no, product_id, main_code,
					 description, quantity, unit_price, discount, subtotal, tax_code, tax_rate_code, tax_rate, tax_amount)
					VALUES (:tenantId, :documentId, :lineNo, :productId, :mainCode, :description, :quantity,
					 :unitPrice, :discount, :subtotal, :taxCode, :taxRateCode, :taxRate, :taxAmount)
					""")
				.param("tenantId", tenantId)
				.param("documentId", id)
				.param("lineNo", i + 1)
				.param("productId", line.productId())
				.param("mainCode", line.mainCode())
				.param("description", line.description())
				.param("quantity", line.quantity())
				.param("unitPrice", line.unitPrice())
				.param("discount", line.discount())
				.param("subtotal", line.subtotal())
				.param("taxCode", line.taxCode())
				.param("taxRateCode", line.taxRateCode())
				.param("taxRate", line.taxRate())
				.param("taxAmount", line.taxAmount())
				.update();
		}
		for (TaxTotal tax : document.totals().taxes()) {
			jdbc.sql("""
					INSERT INTO fiscal_document_taxes (tenant_id, document_id, tax_code, rate_code, rate, base, amount)
					VALUES (:tenantId, :documentId, :taxCode, :rateCode, :rate, :base, :amount)
					""")
				.param("tenantId", tenantId)
				.param("documentId", id)
				.param("taxCode", tax.taxCode())
				.param("rateCode", tax.rateCode())
				.param("rate", tax.rate())
				.param("base", tax.base())
				.param("amount", tax.amount())
				.update();
		}
		for (int i = 0; i < payments.size(); i++) {
			jdbc.sql("""
					INSERT INTO fiscal_document_payments (tenant_id, document_id, line_no, method_code, amount)
					VALUES (:tenantId, :documentId, :lineNo, :methodCode, :amount)
					""")
				.param("tenantId", tenantId)
				.param("documentId", id)
				.param("lineNo", i + 1)
				.param("methodCode", payments.get(i).methodCode())
				.param("amount", payments.get(i).amount())
				.update();
		}
		return id;
	}

	public void recordStateChange(UUID tenantId, UUID documentId, String previousState, String newState,
			String reason) {
		jdbc.sql("""
				INSERT INTO state_events (tenant_id, document_id, previous_state, new_state, reason, event_time)
				VALUES (:tenantId, :documentId, :previousState, :newState, :reason, clock_timestamp())
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("previousState", previousState)
			.param("newState", newState)
			.param("reason", reason)
			.update();
	}

	/** Lo que el procesamiento necesita saber del documento. version cambia con cada transición. */
	public record ProcessingState(String state, UUID taxpayerId, String environment, String accessKey,
			int attemptCount, long version) {
	}

	private static final String PROCESSING_COLUMNS = "state, taxpayer_id, environment, access_key, attempt_count, version";

	/**
	 * Bloquea el documento hasta el final de la transacción en curso y devuelve su estado: dos
	 * procesos que lo tomen a la vez avanzan de uno en uno.
	 */
	public Optional<ProcessingState> lockForProcessing(UUID tenantId, UUID documentId) {
		return jdbc
			.sql("SELECT " + PROCESSING_COLUMNS
					+ " FROM fiscal_documents WHERE id = :id AND tenant_id = :tenantId FOR UPDATE")
			.param("id", documentId)
			.param("tenantId", tenantId)
			.query(ProcessingState.class)
			.optional();
	}

	/** Si el emisor del documento tiene activada la emisión en producción. */
	public boolean issuerInProduction(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT t.production_enabled_at IS NOT NULL
				  FROM fiscal_documents d JOIN taxpayers t ON t.tenant_id = d.tenant_id AND t.id = d.taxpayer_id
				 WHERE d.id = :id AND d.tenant_id = :tenantId
				""").param("id", documentId).param("tenantId", tenantId).query(Boolean.class).single();
	}

	/** Estado del documento sin bloquearlo. */
	public Optional<ProcessingState> processingState(UUID tenantId, UUID documentId) {
		return jdbc
			.sql("SELECT " + PROCESSING_COLUMNS + " FROM fiscal_documents WHERE id = :id AND tenant_id = :tenantId")
			.param("id", documentId)
			.param("tenantId", tenantId)
			.query(ProcessingState.class)
			.optional();
	}

	/**
	 * Programa el siguiente intento dentro del plazo indicado (nulo: nada programado) y deja la
	 * cuenta de intentos no concluyentes. No es una transición: no cambia version.
	 */
	public void schedule(UUID tenantId, UUID documentId, Duration delay, int attemptCount) {
		jdbc.sql("""
				UPDATE fiscal_documents
				SET next_attempt_at = now() + make_interval(secs => CAST(:seconds AS double precision)),
				 attempt_count = :attemptCount
				WHERE id = :id AND tenant_id = :tenantId
				""")
			.param("seconds", delay == null ? null : delay.toMillis() / 1000.0)
			.param("attemptCount", attemptCount)
			.param("id", documentId)
			.param("tenantId", tenantId)
			.update();
	}

	/**
	 * Cambia el estado solo si el documento sigue en el estado de partida, y deja el evento. Devuelve
	 * false si otro proceso ya lo movió.
	 */
	public boolean transition(UUID tenantId, UUID documentId, String from, String to, String reason) {
		int updated = jdbc.sql("""
				UPDATE fiscal_documents SET state = :to, version = version + 1, state_changed_at = clock_timestamp()
				WHERE id = :id AND tenant_id = :tenantId AND state = :from
				""").param("to", to).param("id", documentId).param("tenantId", tenantId).param("from", from).update();
		if (updated == 1) {
			recordStateChange(tenantId, documentId, from, to, reason);
		}
		return updated == 1;
	}

	/** Anota el número y la fecha de autorización que devolvió el SRI. */
	public void markAuthorized(UUID tenantId, UUID documentId, String authorizationNumber,
			OffsetDateTime authorizedAt) {
		jdbc.sql("""
				UPDATE fiscal_documents SET authorization_number = :number, authorized_at = :authorizedAt
				WHERE id = :id AND tenant_id = :tenantId
				""")
			.param("number", authorizationNumber)
			.param("authorizedAt", authorizedAt)
			.param("id", documentId)
			.param("tenantId", tenantId)
			.update();
	}

	/** Motivo del evento más reciente que dejó el documento en ese estado. */
	public Optional<String> lastReason(UUID tenantId, UUID documentId, String state) {
		return jdbc.sql("""
				SELECT reason FROM state_events
				WHERE tenant_id = :tenantId AND document_id = :documentId AND new_state = :state
				ORDER BY event_time DESC LIMIT 1
				""")
			.param("tenantId", tenantId)
			.param("documentId", documentId)
			.param("state", state)
			.query(String.class)
			.optional();
	}

	public void setSchemaVersion(UUID tenantId, UUID documentId, String schemaVersion) {
		jdbc.sql("UPDATE fiscal_documents SET schema_version = :version WHERE id = :id AND tenant_id = :tenantId")
			.param("version", schemaVersion)
			.param("id", documentId)
			.param("tenantId", tenantId)
			.update();
	}

	public Optional<DocumentRow> find(UUID tenantId, UUID id) {
		return jdbc.sql("SELECT " + DOCUMENT_COLUMNS + " FROM fiscal_documents WHERE id = :id AND tenant_id = :tenantId")
			.param("id", id)
			.param("tenantId", tenantId)
			.query(DocumentRow.class)
			.optional();
	}

	private record XmlHeader(String environment, String issuerLegalName, String issuerTradeName, String issuerRuc,
			String accessKey, String documentType, String establishmentCode, String emissionPointCode, int sequence,
			String issuerHeadOfficeAddress, String issuerWithholdingAgentCode, String issuerRimpeRegime,
			LocalDate issueDate, String establishmentAddress, String issuerSpecialTaxpayerCode,
			boolean issuerAccountingRequired, String customerIdType, String customerName, String customerIdNumber,
			String customerAddress, BigDecimal subtotal, BigDecimal totalDiscount, BigDecimal total,
			String providerRuc) {
	}

	/** Datos congelados del documento, en la forma que consume el generador del XML. */
	public Optional<InvoiceXmlData> xmlData(UUID tenantId, UUID documentId) {
		Optional<XmlHeader> found = jdbc.sql("""
				SELECT environment, issuer_legal_name, issuer_trade_name, issuer_ruc, access_key, document_type,
				 establishment_code, emission_point_code, sequence, issuer_head_office_address,
				 issuer_withholding_agent_code, issuer_rimpe_regime, issue_date, establishment_address,
				 issuer_special_taxpayer_code, issuer_accounting_required, customer_id_type, customer_name,
				 customer_id_number, customer_address, subtotal, total_discount, total, provider_ruc
				FROM fiscal_documents WHERE id = :id AND tenant_id = :tenantId
				""").param("id", documentId).param("tenantId", tenantId).query(XmlHeader.class).optional();
		if (found.isEmpty()) {
			return Optional.empty();
		}
		XmlHeader h = found.get();
		List<InvoiceXmlData.Tax> taxes = taxes(tenantId, documentId).stream()
			.map(tax -> new InvoiceXmlData.Tax(tax.taxCode(), tax.rateCode(), tax.rate(), tax.base(), tax.amount()))
			.toList();
		List<InvoiceXmlData.Line> lines = lines(tenantId, documentId).stream()
			.map(line -> new InvoiceXmlData.Line(line.mainCode(), line.description(), line.quantity(),
					line.unitPrice(), line.discount(), line.subtotal(), line.taxCode(), line.taxRateCode(),
					line.taxRate(), line.taxAmount()))
			.toList();
		List<InvoiceXmlData.Payment> payments = payments(tenantId, documentId).stream()
			.map(payment -> new InvoiceXmlData.Payment(payment.methodCode(), payment.amount()))
			.toList();
		return Optional.of(new InvoiceXmlData(h.environment(), h.issuerLegalName(), h.issuerTradeName(),
				h.issuerRuc(), h.accessKey(), h.documentType(), h.establishmentCode(), h.emissionPointCode(),
				h.sequence(), h.issuerHeadOfficeAddress(), h.issuerWithholdingAgentCode(), h.issuerRimpeRegime(),
				h.issueDate(), h.establishmentAddress(), h.issuerSpecialTaxpayerCode(), h.issuerAccountingRequired(),
				h.customerIdType(), h.customerName(), h.customerIdNumber(), h.customerAddress(), h.subtotal(),
				h.totalDiscount(), taxes, h.total(), lines, payments, h.providerRuc()));
	}

	/** Correo del cliente congelado en el comprobante; vacío si no lo tiene o el documento no existe. */
	public Optional<String> customerEmail(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT customer_email FROM fiscal_documents
				WHERE id = :id AND tenant_id = :tenantId AND customer_email IS NOT NULL
				""").param("id", documentId).param("tenantId", tenantId).query(String.class).optional();
	}

	private record RideExtras(String authorizationNumber, OffsetDateTime authorizedAt, String customerEmail) {
	}

	private record PaymentMethodName(String code, String name) {
	}

	/** Datos para el RIDE; vacío si el documento no existe en esa empresa o todavía no está autorizado. */
	public Optional<RideData> rideData(UUID tenantId, UUID documentId) {
		Optional<RideExtras> extras = jdbc.sql("""
				SELECT authorization_number, authorized_at, customer_email FROM fiscal_documents
				WHERE id = :id AND tenant_id = :tenantId AND state = 'AUTHORIZED'
				 AND authorization_number IS NOT NULL AND authorized_at IS NOT NULL
				""").param("id", documentId).param("tenantId", tenantId).query(RideExtras.class).optional();
		if (extras.isEmpty()) {
			return Optional.empty();
		}
		InvoiceXmlData invoice = xmlData(tenantId, documentId).orElseThrow();
		Map<String, String> names = new HashMap<>();
		jdbc.sql("SELECT code, name FROM payment_methods").query(PaymentMethodName.class).list()
			.forEach(method -> names.put(method.code(), method.name()));
		RideExtras e = extras.get();
		return Optional.of(new RideData(invoice, e.authorizationNumber(), e.authorizedAt(), e.customerEmail(),
				Map.copyOf(names)));
	}

	public List<DocumentRow> page(UUID tenantId, int page, int size) {
		return jdbc.sql("SELECT " + DOCUMENT_COLUMNS + """
				FROM fiscal_documents WHERE tenant_id = :tenantId
				ORDER BY created_at DESC, sequence DESC, id
				LIMIT :size OFFSET :offset
				""")
			.param("tenantId", tenantId)
			.param("size", size)
			.param("offset", (long) page * size)
			.query(DocumentRow.class)
			.list();
	}

	public long count(UUID tenantId) {
		return jdbc.sql("SELECT count(*) FROM fiscal_documents WHERE tenant_id = :tenantId")
			.param("tenantId", tenantId)
			.query(Long.class)
			.single();
	}

	public Links links(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT d.customer_id, d.replaces_document_id,
				       (SELECT r.id FROM fiscal_documents r
				         WHERE r.tenant_id = d.tenant_id AND r.replaces_document_id = d.id) AS replaced_by_document_id
				  FROM fiscal_documents d WHERE d.id = :id AND d.tenant_id = :tenantId
				""").param("id", documentId).param("tenantId", tenantId).query(Links.class).single();
	}

	/** El comprobante que reemplazó a este, si lo hay. */
	public Optional<UUID> replacementOf(UUID tenantId, UUID documentId) {
		return jdbc.sql("SELECT id FROM fiscal_documents WHERE tenant_id = :tenantId AND replaces_document_id = :id")
			.param("tenantId", tenantId)
			.param("id", documentId)
			.query(UUID.class)
			.optional();
	}

	/** Anota en el comprobante nuevo a cuál reemplaza. */
	public void linkReplacement(UUID tenantId, UUID documentId, UUID replacedId) {
		jdbc.sql("UPDATE fiscal_documents SET replaces_document_id = :replaced WHERE id = :id AND tenant_id = :tenantId")
			.param("replaced", replacedId)
			.param("id", documentId)
			.param("tenantId", tenantId)
			.update();
	}

	public List<LineRow> lines(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT line_no, main_code, description, quantity, unit_price, discount, subtotal, tax_code,
				 tax_rate_code, tax_rate, tax_amount, product_id
				FROM fiscal_document_lines WHERE document_id = :documentId AND tenant_id = :tenantId ORDER BY line_no
				""").param("documentId", documentId).param("tenantId", tenantId).query(LineRow.class).list();
	}

	public List<TaxTotal> taxes(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT tax_code, rate_code, rate, base, amount FROM fiscal_document_taxes
				WHERE document_id = :documentId AND tenant_id = :tenantId
				ORDER BY tax_code, CAST(rate_code AS integer)
				""").param("documentId", documentId).param("tenantId", tenantId).query(TaxTotal.class).list();
	}

	public List<PaymentRow> payments(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT line_no, method_code, amount FROM fiscal_document_payments
				WHERE document_id = :documentId AND tenant_id = :tenantId ORDER BY line_no
				""").param("documentId", documentId).param("tenantId", tenantId).query(PaymentRow.class).list();
	}

}
