package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.billing.FiscalDocumentRepository.NewDocument;
import com.example.einvoice.billing.FiscalDocumentRepository.NewLine;
import com.example.einvoice.billing.FiscalDocumentRepository.NewPayment;
import com.example.einvoice.catalog.CatalogRepository;
import com.example.einvoice.catalog.Customer;
import com.example.einvoice.catalog.CustomerService;
import com.example.einvoice.catalog.Product;
import com.example.einvoice.catalog.ProductService;
import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.sequence.AccessKeyParts;
import com.example.einvoice.sequence.FiscalClock;
import com.example.einvoice.sequence.NumericCodeGenerator;
import com.example.einvoice.sequence.SequenceService;
import com.example.einvoice.taxpayer.Taxpayer;
import com.example.einvoice.taxpayer.TaxpayerService;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Emisión de una factura hasta el estado RESERVED. Todo ocurre en una transacción: si algo falla no
 * queda documento, ni secuencial consumido, ni evento por publicar. Las validaciones van antes de
 * reservar el secuencial para no retener el contador más de lo necesario.
 */
@Service
public class InvoiceService {

	/** Código de factura en el catálogo de tipos de comprobante del SRI. */
	public static final String INVOICE = "01";

	public static final String STATE_RESERVED = "RESERVED";

	/** Tipo de emisión normal: el único del esquema off-line. */
	private static final String NORMAL_EMISSION = "1";

	private static final String CURRENCY = "USD";

	/** Mayor importe que cabe en las columnas numeric(14,2) del documento. */
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

	public record LineCommand(UUID productId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount) {
	}

	public record PaymentCommand(String methodCode, BigDecimal amount) {
	}

	/** Estados en los que el SRI rechazó el comprobante de forma definitiva. */
	/** Fallos sin reintento posible: el SRI lo rechazó, o con esos datos no sale un XML válido. */
	private static final Set<String> REPLACEABLE_STATES = Set.of("RETURNED", "NOT_AUTHORIZED", "XSD_INVALID");

	public record IssueInvoiceCommand(UUID taxpayerId, String establishmentCode, String emissionPointCode,
			UUID customerId, LocalDate issueDate, String currency, List<LineCommand> lines,
			List<PaymentCommand> payments, UUID replacesDocumentId) {
	}

	private record EmissionPointRef(UUID id, boolean enabled, String establishmentAddress) {
	}

	private final TaxpayerService taxpayers;

	private final CustomerService customers;

	private final ProductService products;

	private final CatalogRepository catalogs;

	private final SequenceService sequences;

	private final NumericCodeGenerator numericCodes;

	private final FiscalDocumentRepository documents;

	private final OutboxWriter outbox;

	private final AuditLog audit;

	private final IdempotencyStore idempotency;

	private final JdbcClient jdbc;

	private final BigDecimal finalConsumerLimit;

	/** Plazo tras el cual un comprobante que nadie procesó vuelve a la cola. */
	private final Duration redeliveryLease;

	/** RUC del proveedor del sistema de facturación (ficha, Anexo 26); nulo si no está configurado. */
	private final String providerRuc;

	public InvoiceService(TaxpayerService taxpayers, CustomerService customers, ProductService products,
			CatalogRepository catalogs, SequenceService sequences, NumericCodeGenerator numericCodes,
			FiscalDocumentRepository documents, OutboxWriter outbox, AuditLog audit, IdempotencyStore idempotency,
			JdbcClient jdbc,
			@Value("${einvoice.invoice.final-consumer-limit}") BigDecimal finalConsumerLimit,
			@Value("${einvoice.provider.ruc:}") String providerRuc,
			@Value("${einvoice.worker.redelivery-lease:5m}") Duration redeliveryLease) {
		this.redeliveryLease = redeliveryLease;
		this.taxpayers = taxpayers;
		this.customers = customers;
		this.products = products;
		this.catalogs = catalogs;
		this.sequences = sequences;
		this.numericCodes = numericCodes;
		this.documents = documents;
		this.outbox = outbox;
		this.audit = audit;
		this.idempotency = idempotency;
		this.jdbc = jdbc;
		this.finalConsumerLimit = finalConsumerLimit;
		if (!providerRuc.isBlank() && !providerRuc.matches("[0-9]{13}")) {
			throw new IllegalStateException("einvoice.provider.ruc debe ser un RUC de 13 dígitos");
		}
		this.providerRuc = providerRuc.isBlank() ? null : providerRuc;
	}

	/**
	 * Emite la factura, o devuelve la ya emitida si la clave de idempotencia se repite con la misma
	 * petición. Si la emisión falla, la transacción se revierte y la clave queda libre para reintentar.
	 */
	/**
	 * Solo se reemplaza un comprobante propio que el SRI devolvió o no autorizó, y una sola vez. Lo
	 * deja bloqueado: dos reemisiones a la vez del mismo comprobante se deciden de una en una.
	 */
	private void requireReplaceable(TenantAccess access, UUID replacedId, UUID taxpayerId) {
		var replaced = documents.lockForProcessing(access.tenantId(), replacedId).orElseThrow(ApiException::notFound);
		if (!REPLACEABLE_STATES.contains(replaced.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "REPLACED_DOCUMENT_NOT_REJECTED",
					"Solo se reemplaza un comprobante devuelto o no autorizado por el SRI, o cuyo XML no se pudo generar");
		}
		// El enlace es único y no se deshace: no se gasta en un comprobante de otro RUC.
		if (!replaced.taxpayerId().equals(taxpayerId)) {
			throw new ApiException(HttpStatus.CONFLICT, "REPLACED_DOCUMENT_OTHER_ISSUER",
					"El comprobante que se reemplaza es de otro emisor de la empresa");
		}
		if (documents.replacementOf(access.tenantId(), replacedId).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_REPLACED",
					"Ese comprobante ya fue reemplazado por otro");
		}
	}

	@Transactional
	public UUID issue(TenantAccess access, String idempotencyKey, String requestHash, IssueInvoiceCommand command) {
		access.requireBilling();
		Optional<UUID> previous = idempotency.begin(access.tenantId(), idempotencyKey, requestHash);
		if (previous.isPresent()) {
			return previous.get();
		}
		if (command.replacesDocumentId() != null) {
			requireReplaceable(access, command.replacesDocumentId(), command.taxpayerId());
		}
		Taxpayer taxpayer = taxpayers.get(access, command.taxpayerId());
		EmissionPointRef point = emissionPoint(access, taxpayer, command);
		Customer customer = customers.get(access, command.customerId());
		if (!point.enabled()) {
			throw unprocessable("EMISSION_POINT_DISABLED", "El punto de emisión está deshabilitado");
		}
		String currency = command.currency() == null ? CURRENCY : command.currency();
		if (!CURRENCY.equals(currency)) {
			throw unprocessable("UNSUPPORTED_CURRENCY", "Solo se emiten comprobantes en USD");
		}
		LocalDate today = FiscalClock.dateOf(Instant.now());
		LocalDate issueDate = command.issueDate() == null ? today : command.issueDate();
		if (issueDate.isAfter(today)) {
			throw unprocessable("INVALID_ISSUE_DATE", "La fecha de emisión no puede ser futura");
		}

		List<Product> lineProducts = new ArrayList<>();
		List<InvoiceLineInput> inputs = new ArrayList<>();
		for (LineCommand line : command.lines()) {
			Product product = products.get(access, line.productId());
			BigDecimal rate = catalogs.ivaRate(product.getIvaRateCode())
				.orElseThrow(() -> unprocessable("TAX_RATE_UNKNOWN", "La tarifa de IVA del producto no existe"));
			lineProducts.add(product);
			inputs.add(new InvoiceLineInput(line.quantity(),
					line.unitPrice() == null ? product.getUnitPrice() : line.unitPrice(),
					line.discount() == null ? BigDecimal.ZERO : line.discount(), product.getIvaRateCode(), rate));
		}
		InvoiceTotals totals;
		try {
			totals = InvoiceCalculator.calculate(inputs);
		}
		catch (IllegalArgumentException ex) {
			throw unprocessable("INVALID_LINE", ex.getMessage());
		}

		boolean storable = totals.lines().stream().allMatch(line -> line.subtotal().compareTo(MAX_AMOUNT) <= 0)
				&& totals.subtotal().compareTo(MAX_AMOUNT) <= 0 && totals.totalDiscount().compareTo(MAX_AMOUNT) <= 0
				&& totals.total().compareTo(MAX_AMOUNT) <= 0;
		if (!storable) {
			throw unprocessable("AMOUNT_TOO_LARGE", "Los importes del comprobante superan el máximo admitido");
		}

		BigDecimal paid = BigDecimal.ZERO;
		for (PaymentCommand payment : command.payments()) {
			// Un pago en cero solo tiene sentido cuando el comprobante entero vale cero.
			if (payment.amount().signum() == 0 && totals.total().signum() != 0) {
				throw unprocessable("INVALID_PAYMENT", "Un pago no puede ser cero si el comprobante tiene valor");
			}
			if (!catalogs.paymentMethodValidOn(payment.methodCode(), issueDate)) {
				throw unprocessable("PAYMENT_METHOD_UNKNOWN",
						"La forma de pago no existe o no está vigente en la fecha de emisión");
			}
			paid = paid.add(payment.amount());
		}
		if (paid.compareTo(totals.total()) != 0) {
			throw unprocessable("PAYMENTS_MISMATCH", "La suma de los pagos no coincide con el total del comprobante");
		}
		boolean finalConsumer = catalogs.identificationType(customer.getIdType())
			.map(CatalogRepository.IdentificationType::finalConsumer)
			.orElse(false);
		if (finalConsumer && totals.total().compareTo(finalConsumerLimit) > 0) {
			throw unprocessable("FINAL_CONSUMER_LIMIT_EXCEEDED",
					"Una venta a consumidor final no puede superar el monto máximo; identifique al adquirente");
		}

		String environment = taxpayer.getDefaultEnvironment();
		int sequence = sequences.reserve(access.tenantId(), point.id(), environment, INVOICE, issueDate);
		String accessKey = new AccessKeyParts(issueDate, INVOICE, taxpayer.getRuc(), environment,
				command.establishmentCode(), command.emissionPointCode(), sequence, numericCodes.next(),
				NORMAL_EMISSION)
			.toAccessKey();

		List<NewLine> lines = new ArrayList<>();
		for (int i = 0; i < inputs.size(); i++) {
			InvoiceLineInput input = inputs.get(i);
			Product product = lineProducts.get(i);
			LineAmounts amounts = totals.lines().get(i);
			lines.add(new NewLine(product.getId(), product.getSku(), product.getName(), input.quantity(),
					input.unitPrice(), input.discount(), amounts.subtotal(), CatalogRepository.IVA_TAX_CODE,
					input.ivaRateCode(), input.ivaRate(), amounts.taxAmount()));
		}
		List<NewPayment> payments = command.payments()
			.stream()
			.map(payment -> new NewPayment(payment.methodCode(), payment.amount()))
			.toList();
		UUID documentId = documents.insert(new NewDocument(access.tenantId(), taxpayer.getId(), point.id(),
				customer.getId(), INVOICE, environment, taxpayer.getRuc(), command.establishmentCode(),
				command.emissionPointCode(), sequence, accessKey, STATE_RESERVED, issueDate, currency,
				customer.getIdType(), customer.getIdNumber(), customer.getName(), customer.getAddress(),
				customer.getEmail(), totals, access.userId(), taxpayer.getLegalName(), taxpayer.getTradeName(),
				taxpayer.getHeadOfficeAddress(), point.establishmentAddress(), taxpayer.isAccountingRequired(),
				taxpayer.getSpecialTaxpayerCode(), taxpayer.getWithholdingAgentCode(),
				taxpayer.getRimpeRegime().name(), providerRuc), lines, payments);
		documents.recordStateChange(access.tenantId(), documentId, null, STATE_RESERVED, null);
		audit.record(access.tenantId(), access.userId(), "INVOICE_RESERVED", "FISCAL_DOCUMENT", documentId,
				Map.of("sequence", sequence, "environment", environment));
		outbox.write(access.tenantId(), documentId, "INVOICE_RESERVED", Map.of("documentId", documentId.toString()));
		if (command.replacesDocumentId() != null) {
			documents.linkReplacement(access.tenantId(), documentId, command.replacesDocumentId());
			audit.record(access.tenantId(), access.userId(), "DOCUMENT_REPLACED", "FISCAL_DOCUMENT",
					command.replacesDocumentId(), Map.of("replacedBy", documentId.toString()));
		}
		// Red de seguridad: si ese evento se pierde o nadie logra procesarlo, el planificador retoma el documento.
		documents.schedule(access.tenantId(), documentId, redeliveryLease, 0);
		idempotency.complete(access.tenantId(), idempotencyKey, documentId);
		return documentId;
	}

	/** El punto de emisión se localiza por los códigos del comprobante, siempre dentro del emisor y del tenant. */
	private EmissionPointRef emissionPoint(TenantAccess access, Taxpayer taxpayer, IssueInvoiceCommand command) {
		return jdbc.sql("""
				SELECT ep.id, ep.enabled, e.address AS establishment_address
				FROM emission_points ep JOIN establishments e ON e.id = ep.establishment_id
				WHERE e.tenant_id = :tenant AND e.taxpayer_id = :taxpayer
				  AND e.code = :establishmentCode AND ep.code = :emissionPointCode
				""")
			.param("tenant", access.tenantId())
			.param("taxpayer", taxpayer.getId())
			.param("establishmentCode", command.establishmentCode())
			.param("emissionPointCode", command.emissionPointCode())
			.query(EmissionPointRef.class)
			.optional()
			.orElseThrow(ApiException::notFound);
	}

	private static ApiException unprocessable(String errorCode, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, detail);
	}

}
