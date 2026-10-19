package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.einvoice.billing.DocumentIssuer.IssueRequest;
import com.example.einvoice.billing.DocumentIssuer.Preparation;
import com.example.einvoice.billing.FiscalDocumentRepository.NewLine;
import com.example.einvoice.billing.FiscalDocumentRepository.NewPayment;
import com.example.einvoice.catalog.CatalogRepository;
import com.example.einvoice.catalog.Customer;
import com.example.einvoice.catalog.CustomerService;
import com.example.einvoice.catalog.Product;
import com.example.einvoice.catalog.ProductService;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Lo propio de la factura en la emisión: productos, cálculo, importes máximos, pagos y límite de consumidor
 * final. Lo común (idempotencia, reemisión, emisor, punto, secuencial, XML, outbox) lo hace
 * {@link DocumentIssuer}, en una sola transacción con esta parte.
 */
@Service
public class InvoiceService {

	/** Mayor importe que cabe en las columnas numeric(14,2) del documento. */
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

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

	private final ProductService products;

	private final CatalogRepository catalogs;

	private final BigDecimal finalConsumerLimit;

	public InvoiceService(DocumentIssuer issuer, InvoiceKind kind, CustomerService customers,
			ProductService products, CatalogRepository catalogs,
			@Value("${einvoice.invoice.final-consumer-limit}") BigDecimal finalConsumerLimit) {
		this.issuer = issuer;
		this.kind = kind;
		this.customers = customers;
		this.products = products;
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
		return new Preparation<>(kind, customer,
				new DocumentEnvelope.Totals(totals.subtotal(), totals.totalDiscount(), totals.totalTax(),
						totals.total()),
				null, new InvoiceBody(lines, totals.taxes(), payments));
	}

	private static ApiException unprocessable(String errorCode, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, detail);
	}

}
