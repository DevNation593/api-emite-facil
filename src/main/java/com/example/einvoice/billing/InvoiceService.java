package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.einvoice.billing.CatalogLines.CatalogLine;
import com.example.einvoice.billing.DocumentIssuer.IssueRequest;
import com.example.einvoice.billing.DocumentIssuer.Preparation;
import com.example.einvoice.billing.FiscalDocumentRepository.NewLine;
import com.example.einvoice.billing.FiscalDocumentRepository.NewPayment;
import com.example.einvoice.catalog.CatalogRepository;
import com.example.einvoice.catalog.Customer;
import com.example.einvoice.catalog.CustomerService;
import com.example.einvoice.catalog.Product;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Lo propio de la factura en la emisión: productos, cálculo, importes máximos, pagos y límite de consumidor
 * final. Lo común (idempotencia, reemisión, emisor, punto, secuencial, XML, outbox) lo hace
 * {@link DocumentIssuer}, en una sola transacción con esta parte.
 */
@Service
public class InvoiceService {

	public record LineCommand(UUID productId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount) {
	}

	public record PaymentCommand(String methodCode, BigDecimal amount) {
	}

	public record IssueInvoiceCommand(UUID taxpayerId, String establishmentCode, String emissionPointCode,
			UUID customerId, LocalDate issueDate, String currency, List<LineCommand> lines,
			List<PaymentCommand> payments, UUID replacesDocumentId) {
	}

	private final DocumentIssuer issuer;

	private final InvoiceKind kind;

	private final CustomerService customers;

	private final CatalogLines catalogLines;

	private final CatalogRepository catalogs;

	private final FinalConsumerLimit finalConsumerLimit;

	public InvoiceService(DocumentIssuer issuer, InvoiceKind kind, CustomerService customers,
			CatalogLines catalogLines, CatalogRepository catalogs,
			FinalConsumerLimit finalConsumerLimit) {
		this.issuer = issuer;
		this.kind = kind;
		this.customers = customers;
		this.catalogLines = catalogLines;
		this.catalogs = catalogs;
		this.finalConsumerLimit = finalConsumerLimit;
	}

	/**
	 * Emite la factura, o devuelve la ya emitida si la clave de idempotencia se repite con la misma
	 * petición. Si la emisión falla, la transacción se revierte y la clave queda libre para reintentar.
	 */
	public UUID issue(TenantAccess access, String idempotencyKey, String requestHash, IssueInvoiceCommand command) {
		IssueRequest request = new IssueRequest(command.taxpayerId(), command.establishmentCode(),
				command.emissionPointCode(), command.issueDate(), command.currency(), command.replacesDocumentId());
		return issuer.issue(access, idempotencyKey, requestHash, request,
				context -> prepare(access, command, context.issueDate()));
	}

	/** Valida y calcula la factura; corre dentro de la transacción de la emisión, antes de reservar el secuencial. */
	private Preparation<InvoiceBody> prepare(TenantAccess access, IssueInvoiceCommand command, LocalDate issueDate) {
		Customer customer = customers.get(access, command.customerId());
		List<CatalogLine> resolved = catalogLines.resolve(access, command.lines());
		InvoiceTotals totals = CatalogLines.calculate(resolved.stream().map(CatalogLine::input).toList());

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
		finalConsumerLimit.check(customer.getIdType(), totals.total());

		List<NewLine> lines = new ArrayList<>();
		for (int i = 0; i < resolved.size(); i++) {
			InvoiceLineInput input = resolved.get(i).input();
			Product product = resolved.get(i).product();
			LineAmounts amounts = totals.lines().get(i);
			lines.add(new NewLine(product.getId(), product.getSku(), product.getName(), input.quantity(),
					input.unitPrice(), input.discount(), amounts.subtotal(), CatalogRepository.IVA_TAX_CODE,
					input.ivaRateCode(), input.ivaRate(), amounts.taxAmount()));
		}
		List<NewPayment> payments = command.payments()
			.stream()
			.map(payment -> new NewPayment(payment.methodCode(), payment.amount()))
			.toList();
		return Preparation.forCustomer(kind, customer,
				new DocumentEnvelope.Totals(totals.subtotal(), totals.totalDiscount(), totals.totalTax(),
						totals.total()),
				null, new InvoiceBody(lines, totals.taxes(), payments));
	}

	private static ApiException unprocessable(String errorCode, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, detail);
	}

}
