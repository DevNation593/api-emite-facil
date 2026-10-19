package com.example.einvoice.notes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.einvoice.billing.CatalogLines;
import com.example.einvoice.billing.CatalogLines.CatalogLine;
import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.DocumentIssuer;
import com.example.einvoice.billing.DocumentIssuer.IssueRequest;
import com.example.einvoice.billing.DocumentIssuer.Preparation;
import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.FiscalDocumentRepository.LineRow;
import com.example.einvoice.billing.InvoiceLineInput;
import com.example.einvoice.billing.InvoiceService.LineCommand;
import com.example.einvoice.billing.InvoiceTotals;
import com.example.einvoice.billing.LineAmounts;
import com.example.einvoice.billing.TaxTotal;
import com.example.einvoice.catalog.CatalogRepository;
import com.example.einvoice.catalog.Customer;
import com.example.einvoice.catalog.CustomerService;
import com.example.einvoice.catalog.Product;
import com.example.einvoice.notes.ModifiedInvoices.ModifiedInvoice;
import com.example.einvoice.notes.ModifiedInvoices.WrittenInvoice;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.web.ApiException.FieldError;

/**
 * Lo propio de la nota de crédito en la emisión. Sobre una factura de la plataforma: la bloquea, copia sus líneas
 * (que solo bajan) y su comprador, y cuida que las notas vigentes no superen su total. Sobre una factura escrita a
 * mano: líneas del catálogo y el cliente indicado, como en la factura. El cálculo es el de la factura. Lo común lo
 * hace {@link DocumentIssuer}, en la misma transacción.
 */
@Service
public class CreditNoteService {

	/**
	 * Una línea pedida: con factura de la plataforma, invoiceLineNo y sin productId; a mano, productId y sin
	 * invoiceLineNo. Precio y descuento nulos toman los de la línea original (o del producto y cero, a mano).
	 */
	public record CreditLineCommand(Integer invoiceLineNo, UUID productId, BigDecimal quantity, BigDecimal unitPrice,
			BigDecimal discount) {
	}

	/** issueDate nulo es hoy; invoiceId, o writtenInvoice con customerId. */
	public record IssueCreditNoteCommand(UUID taxpayerId, String establishmentCode, String emissionPointCode,
			LocalDate issueDate, String reason, UUID invoiceId, WrittenInvoice writtenInvoice, UUID customerId,
			List<CreditLineCommand> lines) {
	}

	private final DocumentIssuer issuer;

	private final CreditNoteKind kind;

	private final ModifiedInvoices invoices;

	private final FiscalDocumentRepository documents;

	private final CustomerService customers;

	private final CatalogLines catalogLines;

	public CreditNoteService(DocumentIssuer issuer, CreditNoteKind kind, ModifiedInvoices invoices,
			FiscalDocumentRepository documents, CustomerService customers, CatalogLines catalogLines) {
		this.issuer = issuer;
		this.kind = kind;
		this.invoices = invoices;
		this.documents = documents;
		this.customers = customers;
		this.catalogLines = catalogLines;
	}

	/**
	 * Emite la nota, o devuelve la ya emitida si la clave de idempotencia se repite con la misma petición. Si
	 * algo falla, la transacción se revierte: ni nota, ni secuencial, ni saldo consumido.
	 */
	public UUID issue(TenantAccess access, String idempotencyKey, String requestHash, IssueCreditNoteCommand command) {
		ModifiedInvoices.requireOneModifiedDocument(command.invoiceId(), command.writtenInvoice(), command.customerId());
		requireLineShape(command);
		IssueRequest request = new IssueRequest(command.taxpayerId(), command.establishmentCode(),
				command.emissionPointCode(), command.issueDate(), null, null);
		return issuer.issue(access, idempotencyKey, requestHash, request,
				context -> command.invoiceId() == null ? fromWrittenInvoice(access, command, context.issueDate())
						: fromInvoice(access, command, context.issueDate()));
	}

	/**
	 * 400 VALIDATION_FAILED si una línea no tiene la forma de su caso: con factura de la plataforma, invoiceLineNo
	 * sin productId y sin repetir la línea; a mano, productId sin invoiceLineNo.
	 */
	private static void requireLineShape(IssueCreditNoteCommand command) {
		boolean fromInvoice = command.invoiceId() != null;
		List<FieldError> errors = new ArrayList<>();
		Set<Integer> invoiceLines = new HashSet<>();
		for (int i = 0; i < command.lines().size(); i++) {
			CreditLineCommand line = command.lines().get(i);
			String field = "lines[" + i + "]";
			if (fromInvoice && (line.invoiceLineNo() == null || line.productId() != null)) {
				errors.add(new FieldError(field, "Con invoiceId, la línea lleva invoiceLineNo y no productId"));
			}
			else if (fromInvoice && !invoiceLines.add(line.invoiceLineNo())) {
				errors.add(new FieldError(field + ".invoiceLineNo", "La línea de la factura se repite"));
			}
			else if (!fromInvoice && (line.productId() == null || line.invoiceLineNo() != null)) {
				errors.add(new FieldError(field, "Sin invoiceId, la línea lleva productId y no invoiceLineNo"));
			}
		}
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
					"La petición no cumple las reglas de validación", errors);
		}
	}

	/** Sobre una factura de la plataforma; corre antes de reservar el secuencial, con la factura bloqueada. */
	private Preparation<CreditNoteContent> fromInvoice(TenantAccess access, IssueCreditNoteCommand command,
			LocalDate issueDate) {
		ModifiedInvoice invoice = invoices.lockForNote(access, command.invoiceId(), command.taxpayerId());
		ModifiedInvoices.requireNotLater(invoice.issueDate(), issueDate);
		List<LineRow> originals = new ArrayList<>();
		List<InvoiceLineInput> inputs = new ArrayList<>();
		for (CreditLineCommand line : command.lines()) {
			LineRow original = invoice.line(line.invoiceLineNo())
				.orElseThrow(() -> unprocessable("CREDIT_LINE_UNKNOWN",
						"La factura no tiene la línea " + line.invoiceLineNo()));
			BigDecimal unitPrice = line.unitPrice() == null ? original.unitPrice() : line.unitPrice();
			if (line.quantity().compareTo(original.quantity()) > 0 || unitPrice.compareTo(original.unitPrice()) > 0) {
				throw unprocessable("CREDIT_LINE_EXCEEDS_INVOICE", "La línea " + line.invoiceLineNo()
						+ " supera la cantidad o el precio unitario de la factura");
			}
			originals.add(original);
			inputs.add(new InvoiceLineInput(line.quantity(), unitPrice,
					line.discount() == null ? original.discount() : line.discount(), original.taxRateCode(),
					original.taxRate()));
		}
		InvoiceTotals totals = CatalogLines.calculate(inputs);
		BigDecimal credited = documents.creditedTotal(access.tenantId(), invoice.id());
		if (credited.add(totals.total()).compareTo(invoice.total()) > 0) {
			throw unprocessable("CREDIT_EXCEEDS_INVOICE",
					"Las notas de crédito de la factura superarían su total; saldo disponible: "
							+ invoice.total().subtract(credited).toPlainString());
		}
		Customer buyer = customers.get(access, invoice.customerId());

		List<CreditNoteContent.Line> lines = new ArrayList<>();
		for (int i = 0; i < inputs.size(); i++) {
			LineRow original = originals.get(i);
			lines.add(line(original.lineNo(), original.productId(), original.mainCode(), original.description(),
					original.taxCode(), inputs.get(i), totals.lines().get(i)));
		}
		return preparation(buyer, totals, invoice.modifiedDocument(), command.reason(), lines);
	}

	/** Sobre una factura escrita a mano: el cliente y las líneas salen del catálogo, como en la factura. */
	private Preparation<CreditNoteContent> fromWrittenInvoice(TenantAccess access, IssueCreditNoteCommand command,
			LocalDate issueDate) {
		WrittenInvoice written = command.writtenInvoice();
		ModifiedInvoices.requireNotLater(written.issueDate(), issueDate);
		Customer buyer = customers.get(access, command.customerId());
		List<CatalogLine> resolved = catalogLines.resolve(access,
				command.lines()
					.stream()
					.map(line -> new LineCommand(line.productId(), line.quantity(), line.unitPrice(), line.discount()))
					.toList());
		InvoiceTotals totals = CatalogLines.calculate(resolved.stream().map(CatalogLine::input).toList());

		List<CreditNoteContent.Line> lines = new ArrayList<>();
		for (int i = 0; i < resolved.size(); i++) {
			Product product = resolved.get(i).product();
			lines.add(line(null, product.getId(), product.getSku(), product.getName(), CatalogRepository.IVA_TAX_CODE,
					resolved.get(i).input(), totals.lines().get(i)));
		}
		return preparation(buyer, totals, written.modifiedDocument(), command.reason(), lines);
	}

	private static CreditNoteContent.Line line(Integer invoiceLineNo, UUID productId, String mainCode,
			String description, String taxCode, InvoiceLineInput input, LineAmounts amounts) {
		return new CreditNoteContent.Line(invoiceLineNo, productId, mainCode, description, input.quantity(),
				input.unitPrice(), input.discount(), amounts.subtotal(), taxCode, input.ivaRateCode(), input.ivaRate(),
				amounts.taxAmount());
	}

	/** El total de la nota es su valorModificacion. */
	private Preparation<CreditNoteContent> preparation(Customer buyer, InvoiceTotals totals,
			DocumentEnvelope.ModifiedDocument modified, String reason, List<CreditNoteContent.Line> lines) {
		List<NoteTax> taxes = totals.taxes().stream().map(CreditNoteService::noteTax).toList();
		return new Preparation<>(kind, buyer,
				new DocumentEnvelope.Totals(totals.subtotal(), totals.totalDiscount(), totals.totalTax(),
						totals.total()),
				modified, new CreditNoteContent(reason, lines, taxes));
	}

	private static NoteTax noteTax(TaxTotal tax) {
		return new NoteTax(tax.taxCode(), tax.rateCode(), tax.rate(), tax.base(), tax.amount());
	}

	private static ApiException unprocessable(String errorCode, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, detail);
	}

}
