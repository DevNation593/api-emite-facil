package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.sql.Types;
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

import com.example.einvoice.xml.DocumentType;

/** Lectura y escritura de documentos fiscales. Toda lectura filtra por tenant. */
@Repository
public class FiscalDocumentRepository {

	/**
	 * Sobre de un comprobante nuevo, tal como se generó y validó su XML. contentJson es el contenido propio del
	 * tipo para la columna content; nulo en la factura.
	 */
	public record NewDocument(UUID tenantId, UUID taxpayerId, UUID emissionPointId, UUID customerId, String state,
			UUID createdBy, DocumentEnvelope envelope, String contentJson) {
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

	/**
	 * Inserta el sobre y devuelve el id. La moneda es siempre USD: la emisión no admite otra. Las tablas
	 * propias del tipo (las de la factura) se llenan después, con {@link DocumentKind#saveDetails}.
	 */
	public UUID insert(NewDocument document) {
		DocumentEnvelope envelope = document.envelope();
		DocumentEnvelope.Issuer issuer = envelope.issuer();
		DocumentEnvelope.Recipient recipient = envelope.recipient();
		DocumentEnvelope.Totals totals = envelope.totals();
		DocumentEnvelope.ModifiedDocument modified = envelope.modified();
		return jdbc.sql("""
				INSERT INTO fiscal_documents (tenant_id, taxpayer_id, emission_point_id, customer_id, document_type,
				 environment, issuer_ruc, establishment_code, emission_point_code, sequence, access_key, state,
				 issue_date, currency, customer_id_type, customer_id_number, customer_name, customer_address,
				 customer_email, subtotal, total_discount, total_tax, total, created_by, issuer_legal_name,
				 issuer_trade_name, issuer_head_office_address, establishment_address, issuer_accounting_required,
				 issuer_special_taxpayer_code, issuer_withholding_agent_code, issuer_rimpe_regime, provider_ruc,
				 modified_document_id, modified_doc_code, modified_doc_number, modified_doc_date, content)
				VALUES (:tenantId, :taxpayerId, :emissionPointId, :customerId, :documentType, :environment,
				 :issuerRuc, :establishmentCode, :emissionPointCode, :sequence, :accessKey, :state, :issueDate,
				 'USD', :customerIdType, :customerIdNumber, :customerName, :customerAddress, :customerEmail,
				 :subtotal, :totalDiscount, :totalTax, :total, :createdBy, :issuerLegalName, :issuerTradeName,
				 :issuerHeadOfficeAddress, :establishmentAddress, :issuerAccountingRequired,
				 :issuerSpecialTaxpayerCode, :issuerWithholdingAgentCode, :issuerRimpeRegime, :providerRuc,
				 :modifiedDocumentId, :modifiedDocCode, :modifiedDocNumber, :modifiedDocDate, CAST(:content AS jsonb))
				RETURNING id
				""")
			.param("tenantId", document.tenantId())
			.param("taxpayerId", document.taxpayerId())
			.param("emissionPointId", document.emissionPointId())
			.param("customerId", document.customerId())
			.param("documentType", envelope.type().code())
			.param("environment", envelope.environment())
			.param("issuerRuc", issuer.ruc())
			.param("establishmentCode", envelope.establishmentCode())
			.param("emissionPointCode", envelope.emissionPointCode())
			.param("sequence", envelope.sequence())
			.param("accessKey", envelope.accessKey())
			.param("state", document.state())
			.param("issueDate", envelope.issueDate())
			.param("customerIdType", recipient.idType())
			.param("customerIdNumber", recipient.idNumber())
			.param("customerName", recipient.name())
			.param("customerAddress", recipient.address())
			.param("customerEmail", recipient.email())
			.param("subtotal", totals == null ? null : totals.subtotal(), Types.NUMERIC)
			.param("totalDiscount", totals == null ? null : totals.totalDiscount(), Types.NUMERIC)
			.param("totalTax", totals == null ? null : totals.totalTax(), Types.NUMERIC)
			.param("total", totals == null ? null : totals.total(), Types.NUMERIC)
			.param("createdBy", document.createdBy())
			.param("issuerLegalName", issuer.legalName())
			.param("issuerTradeName", issuer.tradeName())
			.param("issuerHeadOfficeAddress", issuer.headOfficeAddress())
			.param("establishmentAddress", issuer.establishmentAddress())
			.param("issuerAccountingRequired", issuer.accountingRequired())
			.param("issuerSpecialTaxpayerCode", issuer.specialTaxpayerCode())
			.param("issuerWithholdingAgentCode", issuer.withholdingAgentCode())
			.param("issuerRimpeRegime", issuer.rimpeRegime())
			.param("providerRuc", envelope.providerRuc())
			.param("modifiedDocumentId", modified == null ? null : modified.documentId(), Types.OTHER)
			.param("modifiedDocCode", modified == null ? null : modified.code(), Types.VARCHAR)
			.param("modifiedDocNumber", modified == null ? null : modified.number(), Types.VARCHAR)
			.param("modifiedDocDate", modified == null ? null : modified.issueDate(), Types.DATE)
			.param("content", document.contentJson(), Types.VARCHAR)
			.query(UUID.class)
			.single();
	}

	/** Líneas, impuestos por tarifa y pagos de una factura ya insertada; las líneas y los pagos se numeran desde 1. */
	public void insertInvoiceBody(UUID tenantId, UUID documentId, InvoiceBody body) {
		List<NewLine> lines = body.lines();
		for (int i = 0; i < lines.size(); i++) {
			NewLine line = lines.get(i);
			jdbc.sql("""
					INSERT INTO fiscal_document_lines (tenant_id, document_id, line_no, product_id, main_code,
					 description, quantity, unit_price, discount, subtotal, tax_code, tax_rate_code, tax_rate, tax_amount)
					VALUES (:tenantId, :documentId, :lineNo, :productId, :mainCode, :description, :quantity,
					 :unitPrice, :discount, :subtotal, :taxCode, :taxRateCode, :taxRate, :taxAmount)
					""")
				.param("tenantId", tenantId)
				.param("documentId", documentId)
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
		for (TaxTotal tax : body.taxes()) {
			jdbc.sql("""
					INSERT INTO fiscal_document_taxes (tenant_id, document_id, tax_code, rate_code, rate, base, amount)
					VALUES (:tenantId, :documentId, :taxCode, :rateCode, :rate, :base, :amount)
					""")
				.param("tenantId", tenantId)
				.param("documentId", documentId)
				.param("taxCode", tax.taxCode())
				.param("rateCode", tax.rateCode())
				.param("rate", tax.rate())
				.param("base", tax.base())
				.param("amount", tax.amount())
				.update();
		}
		List<NewPayment> payments = body.payments();
		for (int i = 0; i < payments.size(); i++) {
			jdbc.sql("""
					INSERT INTO fiscal_document_payments (tenant_id, document_id, line_no, method_code, amount)
					VALUES (:tenantId, :documentId, :lineNo, :methodCode, :amount)
					""")
				.param("tenantId", tenantId)
				.param("documentId", documentId)
				.param("lineNo", i + 1)
				.param("methodCode", payments.get(i).methodCode())
				.param("amount", payments.get(i).amount())
				.update();
		}
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
	public record ProcessingState(String state, UUID taxpayerId, String documentType, String environment,
			String accessKey, int attemptCount, long version) {
	}

	private static final String PROCESSING_COLUMNS = "state, taxpayer_id, document_type, environment, access_key,"
			+ " attempt_count, version";

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

	/** Versión del esquema con que se generó el documento; vacío si aún no se generó. */
	public Optional<String> schemaVersion(UUID tenantId, UUID documentId) {
		return jdbc.sql("SELECT schema_version FROM fiscal_documents WHERE id = :id AND tenant_id = :tenantId")
			.param("id", documentId)
			.param("tenantId", tenantId)
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

	/** Correo del cliente congelado en el comprobante; vacío si no lo tiene o el documento no existe. */
	public Optional<String> customerEmail(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT customer_email FROM fiscal_documents
				WHERE id = :id AND tenant_id = :tenantId AND customer_email IS NOT NULL
				""").param("id", documentId).param("tenantId", tenantId).query(String.class).optional();
	}

	private record PaymentMethodName(String code, String name) {
	}

	/** Nombre de cada forma de pago del catálogo del SRI, por código. */
	public Map<String, String> paymentMethodNames() {
		Map<String, String> names = new HashMap<>();
		jdbc.sql("SELECT code, name FROM payment_methods").query(PaymentMethodName.class).list()
			.forEach(method -> names.put(method.code(), method.name()));
		return Map.copyOf(names);
	}

	private record EnvelopeRow(String documentType, String environment, String issuerLegalName,
			String issuerTradeName, String issuerRuc, String issuerHeadOfficeAddress, String establishmentAddress,
			boolean issuerAccountingRequired, String issuerSpecialTaxpayerCode, String issuerWithholdingAgentCode,
			String issuerRimpeRegime, String accessKey, String establishmentCode, String emissionPointCode,
			int sequence, LocalDate issueDate, String customerIdType, String customerIdNumber, String customerName,
			String customerAddress, String customerEmail, BigDecimal subtotal, BigDecimal totalDiscount,
			BigDecimal totalTax, BigDecimal total, UUID modifiedDocumentId, String modifiedDocCode,
			String modifiedDocNumber, LocalDate modifiedDocDate, String providerRuc) {

		DocumentEnvelope envelope() {
			return new DocumentEnvelope(DocumentType.of(documentType), environment,
					new DocumentEnvelope.Issuer(issuerLegalName, issuerTradeName, issuerRuc, issuerHeadOfficeAddress,
							establishmentAddress, issuerAccountingRequired, issuerSpecialTaxpayerCode,
							issuerWithholdingAgentCode, issuerRimpeRegime),
					accessKey, establishmentCode, emissionPointCode, sequence, issueDate,
					new DocumentEnvelope.Recipient(customerIdType, customerIdNumber, customerName, customerAddress,
							customerEmail),
					total == null ? null : new DocumentEnvelope.Totals(subtotal, totalDiscount, totalTax, total),
					modifiedDocCode == null ? null
							: new DocumentEnvelope.ModifiedDocument(modifiedDocumentId, modifiedDocCode,
									modifiedDocNumber, modifiedDocDate),
					providerRuc);
		}

	}

	private static final String ENVELOPE_COLUMNS = """
			document_type, environment, issuer_legal_name, issuer_trade_name, issuer_ruc,
			 issuer_head_office_address, establishment_address, issuer_accounting_required,
			 issuer_special_taxpayer_code, issuer_withholding_agent_code, issuer_rimpe_regime, access_key,
			 establishment_code, emission_point_code, sequence, issue_date, customer_id_type, customer_id_number,
			 customer_name, customer_address, customer_email, subtotal, total_discount, total_tax, total,
			 modified_document_id, modified_doc_code, modified_doc_number, modified_doc_date, provider_ruc
			""";

	/** Sobre congelado del comprobante, de cualquier tipo; vacío si no existe en esa empresa. */
	public Optional<DocumentEnvelope> envelope(UUID tenantId, UUID documentId) {
		return jdbc.sql("SELECT " + ENVELOPE_COLUMNS + " FROM fiscal_documents WHERE id = :id AND tenant_id = :tenantId")
			.param("id", documentId)
			.param("tenantId", tenantId)
			.query(EnvelopeRow.class)
			.optional()
			.map(EnvelopeRow::envelope);
	}

	private record AuthorizationRow(String authorizationNumber, OffsetDateTime authorizedAt) {
	}

	/** Sobre y autorización para el RIDE; vacío si el comprobante no existe en esa empresa o no está autorizado. */
	public Optional<RideEnvelope> rideEnvelope(UUID tenantId, UUID documentId) {
		Optional<AuthorizationRow> authorization = jdbc.sql("""
				SELECT authorization_number, authorized_at FROM fiscal_documents
				WHERE id = :id AND tenant_id = :tenantId AND state = 'AUTHORIZED'
				 AND authorization_number IS NOT NULL AND authorized_at IS NOT NULL
				""").param("id", documentId).param("tenantId", tenantId).query(AuthorizationRow.class).optional();
		return authorization.flatMap(a -> envelope(tenantId, documentId)
			.map(envelope -> new RideEnvelope(envelope, a.authorizationNumber(), a.authorizedAt())));
	}

	/** JSON del contenido propio del comprobante; vacío si no existe en esa empresa o no tiene (la factura). */
	public Optional<String> content(UUID tenantId, UUID documentId) {
		return jdbc.sql("""
				SELECT content::text FROM fiscal_documents
				WHERE id = :id AND tenant_id = :tenantId AND content IS NOT NULL
				""").param("id", documentId).param("tenantId", tenantId).query(String.class).optional();
	}

	/** Cuerpo de la factura: sus líneas, los impuestos por tarifa y los pagos, en su orden. */
	public InvoiceBody invoiceBody(UUID tenantId, UUID documentId) {
		List<NewLine> lines = lines(tenantId, documentId).stream()
			.map(line -> new NewLine(line.productId(), line.mainCode(), line.description(), line.quantity(),
					line.unitPrice(), line.discount(), line.subtotal(), line.taxCode(), line.taxRateCode(),
					line.taxRate(), line.taxAmount()))
			.toList();
		List<NewPayment> payments = payments(tenantId, documentId).stream()
			.map(payment -> new NewPayment(payment.methodCode(), payment.amount()))
			.toList();
		return new InvoiceBody(lines, taxes(tenantId, documentId), payments);
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
