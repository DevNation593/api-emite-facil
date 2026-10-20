package com.example.einvoice.notes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.einvoice.billing.CatalogLines;
import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.DocumentIssuer;
import com.example.einvoice.billing.DocumentIssuer.IssueRequest;
import com.example.einvoice.billing.DocumentIssuer.Preparation;
import com.example.einvoice.billing.FinalConsumerLimit;
import com.example.einvoice.billing.InvoiceService.PaymentCommand;
import com.example.einvoice.catalog.CatalogRepository;
import com.example.einvoice.catalog.Customer;
import com.example.einvoice.catalog.CustomerService;
import com.example.einvoice.notes.ModifiedInvoices.ModifiedInvoice;
import com.example.einvoice.notes.ModifiedInvoices.WrittenInvoice;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Lo propio de la nota de débito en la emisión: motivos con su IVA, un pago por el total y el límite de
 * consumidor final. Sobre una factura de la plataforma toma su comprador; sobre una escrita a mano, el cliente
 * indicado. No hay saldo ni líneas de la factura. Lo común lo hace {@link DocumentIssuer}, en la misma
 * transacción.
 */
@Service
public class DebitNoteService {

	/** Un motivo pedido: su razón, su valor sin IVA y el código de la tarifa de IVA del catálogo. */
	public record ReasonCommand(String reason, BigDecimal value, String ivaRateCode) {
	}

	/** issueDate nulo es hoy; invoiceId, o writtenInvoice con customerId. Exactamente un pago. */
	public record IssueDebitNoteCommand(UUID taxpayerId, String establishmentCode, String emissionPointCode,
			LocalDate issueDate, UUID invoiceId, WrittenInvoice writtenInvoice, UUID customerId,
			List<ReasonCommand> reasons, PaymentCommand payment) {
	}

	private final DocumentIssuer issuer;

	private final DebitNoteKind kind;

	private final ModifiedInvoices invoices;

	private final CustomerService customers;

	private final CatalogLines catalogLines;

	private final CatalogRepository catalogs;

	private final FinalConsumerLimit finalConsumerLimit;

	public DebitNoteService(DocumentIssuer issuer, DebitNoteKind kind, ModifiedInvoices invoices,
			CustomerService customers, CatalogLines catalogLines, CatalogRepository catalogs,
			FinalConsumerLimit finalConsumerLimit) {
		this.issuer = issuer;
		this.kind = kind;
		this.invoices = invoices;
		this.customers = customers;
		this.catalogLines = catalogLines;
		this.catalogs = catalogs;
		this.finalConsumerLimit = finalConsumerLimit;
	}

	/**
	 * Emite la nota, o devuelve la ya emitida si la clave de idempotencia se repite con la misma petición. Si
	 * algo falla, la transacción se revierte: ni nota ni secuencial.
	 */
	public UUID issue(TenantAccess access, String idempotencyKey, String requestHash, IssueDebitNoteCommand command) {
		ModifiedInvoices.requireOneModifiedDocument(command.invoiceId(), command.writtenInvoice(), command.customerId());
		IssueRequest request = new IssueRequest(command.taxpayerId(), command.establishmentCode(),
				command.emissionPointCode(), command.issueDate(), null, null);
		return issuer.issue(access, idempotencyKey, requestHash, request,
				context -> prepare(access, command, context.issueDate()));
	}

	/** Corre antes de reservar el secuencial; con factura de la plataforma, con ella bloqueada. */
	private Preparation<DebitNoteContent> prepare(TenantAccess access, IssueDebitNoteCommand command,
			LocalDate issueDate) {
		UUID customerId;
		DocumentEnvelope.Recipient buyer;
		DocumentEnvelope.ModifiedDocument modified;
		if (command.invoiceId() != null) {
			ModifiedInvoice invoice = invoices.lockForNote(access, command.invoiceId(), command.taxpayerId());
			ModifiedInvoices.requireNotLater(invoice.issueDate(), issueDate);
			customerId = invoice.customerId();
			buyer = invoice.buyer();
			modified = invoice.modifiedDocument();
		}
		else {
			WrittenInvoice written = command.writtenInvoice();
			ModifiedInvoices.requireNotLater(written.issueDate(), issueDate);
			Customer customer = customers.get(access, command.customerId());
			customerId = customer.getId();
			buyer = Preparation.recipientOf(customer);
			modified = written.modifiedDocument();
		}

		DebitNoteCalculator.Result result = calculate(command.reasons());
		CatalogLines.requireStorable(result.subtotal(), result.total());
		PaymentCommand payment = command.payment();
		if (!catalogs.paymentMethodValidOn(payment.methodCode(), issueDate)) {
			throw unprocessable("PAYMENT_METHOD_UNKNOWN",
					"La forma de pago no existe o no está vigente en la fecha de emisión");
		}
		if (payment.amount().compareTo(result.total()) != 0) {
			throw unprocessable("PAYMENTS_MISMATCH", "El pago no coincide con el total del comprobante");
		}
		finalConsumerLimit.check(buyer.idType(), result.total());

		return new Preparation<>(kind, customerId, buyer,
				new DocumentEnvelope.Totals(result.subtotal(), BigDecimal.ZERO, result.totalTax(), result.total()),
				modified,
				new DebitNoteContent(result.reasons(), result.taxes(),
						List.of(new DebitNoteContent.Payment(payment.methodCode(), payment.amount()))));
	}

	/** Cada motivo con la tarifa del catálogo (422 TAX_RATE_UNKNOWN); un valor no positivo es 422 INVALID_LINE. */
	private DebitNoteCalculator.Result calculate(List<ReasonCommand> reasons) {
		List<DebitNoteCalculator.Input> inputs = reasons.stream()
			.map(reason -> new DebitNoteCalculator.Input(reason.reason(), reason.value(), reason.ivaRateCode(),
					catalogLines.ivaRate(reason.ivaRateCode())))
			.toList();
		try {
			return DebitNoteCalculator.calculate(inputs);
		}
		catch (IllegalArgumentException ex) {
			throw unprocessable("INVALID_LINE", ex.getMessage());
		}
	}

	private static ApiException unprocessable(String errorCode, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, detail);
	}

}
