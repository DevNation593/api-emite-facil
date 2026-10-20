package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.billing.FiscalDocumentRepository.DocumentRow;
import com.example.einvoice.notes.CreditNoteContent;
import com.example.einvoice.notes.DebitNoteContent;
import com.example.einvoice.notes.NoteTax;
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

	private final DocumentKinds kinds;

	public DocumentController(FiscalDocumentRepository documents, DocumentKinds kinds) {
		this.documents = documents;
		this.kinds = kinds;
	}

	record CustomerView(String idType, String idNumber, String name) {
	}

	record TaxView(String taxCode, String rateCode, String rate, String base, String amount) {
	}

	/** invoiceLineNo es la línea de la factura que corrige una nota de crédito; nulo en los demás casos. */
	record LineView(int lineNo, Integer invoiceLineNo, UUID productId, String mainCode, String description, String quantity,
			String unitPrice, String discount, String subtotal, String taxCode, String taxRateCode, String taxRate,
			String taxAmount) {
	}

	record PaymentView(String methodCode, String amount) {
	}

	record ModifiedDocumentView(String code, String number, LocalDate issueDate, UUID documentId) {
	}

	record ReasonView(String reason, String value, String taxRateCode, String taxRate, String taxAmount) {
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
			Instant authorizedAt, UUID customerId, UUID replacesDocumentId, UUID replacedByDocumentId,
			ModifiedDocumentView modifiedDocument, String reason, List<ReasonView> reasons, String creditedTotal) {
	}

	@GetMapping("/{id}")
	@Transactional(readOnly = true)
	public DocumentView get(TenantAccess access, @PathVariable UUID id) {
		DocumentRow row = documents.find(access.tenantId(), id).orElseThrow(ApiException::notFound);
		FiscalDocumentRepository.Links links = documents.links(access.tenantId(), id);
		List<TaxView> taxes;
		List<LineView> lines;
		List<PaymentView> payments;
		String reason = null;
		List<ReasonView> reasons = null;
		Object content = isNote(row) ? noteContent(access, row) : null;
		if (content instanceof CreditNoteContent credit) {
			reason = credit.reason();
			taxes = taxViews(credit.taxes());
			lines = IntStream.range(0, credit.lines().size())
				.mapToObj(i -> creditLine(i + 1, credit.lines().get(i)))
				.toList();
			payments = List.of();
		}
		else if (content instanceof DebitNoteContent debit) {
			reasons = debit.reasons().stream().map(DocumentController::debitReason).toList();
			taxes = taxViews(debit.taxes());
			lines = List.of();
			payments = debit.payments()
				.stream()
				.map(payment -> new PaymentView(payment.methodCode(), Decimals.format(payment.amount(), 2)))
				.toList();
		}
		else {
			taxes = documents.taxes(access.tenantId(), id)
				.stream()
				.map(tax -> new TaxView(tax.taxCode(), tax.rateCode(), Decimals.format(tax.rate(), 2),
						Decimals.format(tax.base(), 2), Decimals.format(tax.amount(), 2)))
				.toList();
			lines = documents.lines(access.tenantId(), id)
				.stream()
				.map(line -> new LineView(line.lineNo(), null, line.productId(), line.mainCode(), line.description(),
						Decimals.format(line.quantity(), 6), Decimals.format(line.unitPrice(), 6),
						Decimals.format(line.discount(), 2), Decimals.format(line.subtotal(), 2), line.taxCode(),
						line.taxRateCode(), Decimals.format(line.taxRate(), 2), Decimals.format(line.taxAmount(), 2)))
				.toList();
			payments = documents.payments(access.tenantId(), id)
				.stream()
				.map(payment -> new PaymentView(payment.methodCode(), Decimals.format(payment.amount(), 2)))
				.toList();
		}
		ModifiedDocumentView modified = documents.envelope(access.tenantId(), id)
			.map(DocumentEnvelope::modified)
			.map(m -> new ModifiedDocumentView(m.code(), m.number(), m.issueDate(), m.documentId()))
			.orElse(null);
		String creditedTotal = DocumentType.INVOICE.code().equals(row.documentType())
				? money(documents.creditedTotal(access.tenantId(), id)) : null;
		return new DocumentView(row.id(), row.documentType(), row.state(), row.environment(), row.taxpayerId(),
				formatNumber(row), formatSequence(row), row.accessKey(), row.issueDate(), row.currency(),
				new CustomerView(row.customerIdType(), row.customerIdNumber(), row.customerName()),
				money(row.subtotal()), money(row.totalDiscount()), taxes,
				money(row.totalTax()), money(row.total()), lines, payments,
				row.authorizationNumber(), row.authorizedAt() == null ? null : row.authorizedAt().toInstant(),
				links.customerId(), links.replacesDocumentId(), links.replacedByDocumentId(), modified, reason, reasons,
				creditedTotal);
	}

	private static boolean isNote(DocumentRow row) {
		return DocumentType.CREDIT_NOTE.code().equals(row.documentType())
				|| DocumentType.DEBIT_NOTE.code().equals(row.documentType());
	}

	/** El contenido de una nota se lee con el kind de su tipo. */
	private Object noteContent(TenantAccess access, DocumentRow row) {
		return kinds.find(DocumentType.of(row.documentType()))
			.orElseThrow(() -> new IllegalStateException("Sin kind para " + row.documentType()))
			.loadContent(access.tenantId(), row.id());
	}

	private static List<TaxView> taxViews(List<NoteTax> taxes) {
		return taxes.stream()
			.map(tax -> new TaxView(tax.taxCode(), tax.rateCode(), Decimals.format(tax.rate(), 2),
					Decimals.format(tax.base(), 2), Decimals.format(tax.amount(), 2)))
			.toList();
	}

	private static LineView creditLine(int lineNo, CreditNoteContent.Line line) {
		return new LineView(lineNo, line.invoiceLineNo(), line.productId(), line.mainCode(), line.description(),
				Decimals.format(line.quantity(), 6), Decimals.format(line.unitPrice(), 6),
				Decimals.format(line.discount(), 2), Decimals.format(line.subtotal(), 2), line.taxCode(),
				line.taxRateCode(), Decimals.format(line.taxRate(), 2), Decimals.format(line.taxAmount(), 2));
	}

	private static ReasonView debitReason(DebitNoteContent.Reason reason) {
		return new ReasonView(reason.reason(), Decimals.format(reason.value(), 2), reason.taxRateCode(),
				Decimals.format(reason.taxRate(), 2), Decimals.format(reason.taxAmount(), 2));
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
