package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.billing.FiscalDocumentRepository.DocumentRow;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.web.Decimals;
import com.example.einvoice.web.PageResponse;
import com.example.einvoice.xml.DocumentType;

/** Consulta de comprobantes. Los importes salen como cadenas decimales. */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

	private final FiscalDocumentRepository documents;

	public DocumentController(FiscalDocumentRepository documents) {
		this.documents = documents;
	}

	record CustomerView(String idType, String idNumber, String name) {
	}

	record TaxView(String taxCode, String rateCode, String rate, String base, String amount) {
	}

	record LineView(int lineNo, UUID productId, String mainCode, String description, String quantity,
			String unitPrice, String discount, String subtotal, String taxCode, String taxRateCode, String taxRate,
			String taxAmount) {
	}

	record PaymentView(String methodCode, String amount) {
	}

	record DocumentSummary(UUID id, String documentType, String state, String number, LocalDate issueDate,
			String customerName, String total) {

		static DocumentSummary of(DocumentRow row) {
			return new DocumentSummary(row.id(), row.documentType(), row.state(), formatNumber(row), row.issueDate(),
					row.customerName(), money(row.total()));
		}

	}

	record DocumentView(UUID id, String documentType, String state, String environment, UUID taxpayerId,
			String number, String sequence, String accessKey, LocalDate issueDate, String currency,
			CustomerView customer, String subtotal, String totalDiscount, List<TaxView> taxes, String totalTax,
			String total, List<LineView> lines, List<PaymentView> payments, String authorizationNumber,
			Instant authorizedAt, UUID customerId, UUID replacesDocumentId, UUID replacedByDocumentId) {
	}

	@GetMapping("/{id}")
	@Transactional(readOnly = true)
	public DocumentView get(TenantAccess access, @PathVariable UUID id) {
		DocumentRow row = documents.find(access.tenantId(), id).orElseThrow(ApiException::notFound);
		FiscalDocumentRepository.Links links = documents.links(access.tenantId(), id);
		List<TaxView> taxes = documents.taxes(access.tenantId(), id)
			.stream()
			.map(tax -> new TaxView(tax.taxCode(), tax.rateCode(), Decimals.format(tax.rate(), 2),
					Decimals.format(tax.base(), 2), Decimals.format(tax.amount(), 2)))
			.toList();
		List<LineView> lines = documents.lines(access.tenantId(), id)
			.stream()
			.map(line -> new LineView(line.lineNo(), line.productId(), line.mainCode(), line.description(),
					Decimals.format(line.quantity(), 6), Decimals.format(line.unitPrice(), 6),
					Decimals.format(line.discount(), 2), Decimals.format(line.subtotal(), 2), line.taxCode(),
					line.taxRateCode(), Decimals.format(line.taxRate(), 2), Decimals.format(line.taxAmount(), 2)))
			.toList();
		List<PaymentView> payments = documents.payments(access.tenantId(), id)
			.stream()
			.map(payment -> new PaymentView(payment.methodCode(), Decimals.format(payment.amount(), 2)))
			.toList();
		return new DocumentView(row.id(), row.documentType(), row.state(), row.environment(), row.taxpayerId(),
				formatNumber(row), formatSequence(row), row.accessKey(), row.issueDate(), row.currency(),
				new CustomerView(row.customerIdType(), row.customerIdNumber(), row.customerName()),
				money(row.subtotal()), money(row.totalDiscount()), taxes,
				money(row.totalTax()), money(row.total()), lines, payments,
				row.authorizationNumber(), row.authorizedAt() == null ? null : row.authorizedAt().toInstant(),
				links.customerId(), links.replacesDocumentId(), links.replacedByDocumentId());
	}

	@GetMapping
	@Transactional(readOnly = true)
	public PageResponse<DocumentSummary> list(TenantAccess access, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String documentType) {
		PageResponse.request(page, size);
		DocumentType type = parseType(documentType);
		List<DocumentSummary> items = documents.page(access.tenantId(), type, page, size)
			.stream()
			.map(DocumentSummary::of)
			.toList();
		return new PageResponse<>(items, page, size, documents.count(access.tenantId(), type));
	}

	/** Los totales son opcionales (la guía de remisión no los lleva): nulo sale como nulo. */
	private static String money(BigDecimal value) {
		return value == null ? null : Decimals.format(value, 2);
	}

	private static DocumentType parseType(String code) {
		if (code == null) {
			return null;
		}
		try {
			return DocumentType.of(code);
		}
		catch (IllegalArgumentException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Tipo de comprobante desconocido");
		}
	}

	private static String formatSequence(DocumentRow row) {
		return String.format(Locale.ROOT, "%09d", row.sequence());
	}

	/** Número del comprobante como se imprime: establecimiento-punto-secuencial. */
	private static String formatNumber(DocumentRow row) {
		return row.establishmentCode() + "-" + row.emissionPointCode() + "-" + formatSequence(row);
	}

}
