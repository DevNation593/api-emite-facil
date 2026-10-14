package com.example.einvoice.billing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.billing.InvoiceService.IssueInvoiceCommand;
import com.example.einvoice.billing.InvoiceService.LineCommand;
import com.example.einvoice.billing.InvoiceService.PaymentCommand;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.web.Decimals;

import jakarta.validation.Valid;
import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

	public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

	private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

	private final InvoiceService service;

	private final FiscalDocumentRepository documents;

	private final JsonMapper json;

	public InvoiceController(InvoiceService service, FiscalDocumentRepository documents, JsonMapper json) {
		this.service = service;
		this.documents = documents;
		this.json = json;
	}

	record LineRequest(@NotNull UUID productId,
			@NotNull @jakarta.validation.constraints.Pattern(regexp = Decimals.QUANTITY_OR_PRICE) String quantity,
			@jakarta.validation.constraints.Pattern(regexp = Decimals.QUANTITY_OR_PRICE) String unitPrice,
			@jakarta.validation.constraints.Pattern(regexp = Decimals.AMOUNT) String discount) {
	}

	record PaymentRequest(@NotNull @jakarta.validation.constraints.Pattern(regexp = "[0-9]{2}") String methodCode,
			@NotNull @jakarta.validation.constraints.Pattern(regexp = Decimals.AMOUNT) String amount) {
	}

	record IssueInvoiceRequest(@NotNull UUID taxpayerId,
			@NotNull @jakarta.validation.constraints.Pattern(regexp = "[0-9]{3}") String establishmentCode,
			@NotNull @jakarta.validation.constraints.Pattern(regexp = "[0-9]{3}") String emissionPointCode,
			@NotNull UUID customerId, LocalDate issueDate,
			@jakarta.validation.constraints.Pattern(regexp = "[A-Z]{3}") String currency,
			@NotNull @Size(min = 1, max = 500) List<@NotNull @Valid LineRequest> lines,
			@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid PaymentRequest> payments,
			UUID replacesDocumentId) {

		IssueInvoiceCommand toCommand() {
			return new IssueInvoiceCommand(taxpayerId, establishmentCode, emissionPointCode, customerId, issueDate,
					currency,
					lines.stream()
						.map(line -> new LineCommand(line.productId(), Decimals.parse(line.quantity(), 12, 6),
								line.unitPrice() == null ? null : Decimals.parse(line.unitPrice(), 12, 6),
								line.discount() == null ? null : Decimals.parse(line.discount(), 12, 2)))
						.toList(),
					payments.stream()
						.map(payment -> new PaymentCommand(payment.methodCode(),
								Decimals.parse(payment.amount(), 12, 2)))
						.toList(),
					replacesDocumentId);
		}

	}

	record IssuedResponse(UUID id, String state, String statusUrl) {
	}

	/** Acepta la factura para procesamiento: RESERVED no es todavía un comprobante autorizado. */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	IssuedResponse issue(TenantAccess access,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody IssueInvoiceRequest request) {
		requireIdempotencyKey(idempotencyKey);
		UUID id = service.issue(access, idempotencyKey, hashOf(request), request.toCommand());
		// En un reintento el documento puede haber avanzado: se informa su estado actual.
		String state = documents.find(access.tenantId(), id).orElseThrow(ApiException::notFound).state();
		return new IssuedResponse(id, state, "/api/v1/documents/" + id);
	}

	/**
	 * Huella de la petición ya interpretada: no depende del orden de las claves ni de los espacios
	 * del JSON recibido.
	 */
	private String hashOf(IssueInvoiceRequest request) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static void requireIdempotencyKey(String key) {
		if (key == null || key.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
					"La emisión requiere la cabecera " + IDEMPOTENCY_HEADER);
		}
		if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
					"La cabecera " + IDEMPOTENCY_HEADER + " admite hasta 100 caracteres: letras, dígitos y . _ : -");
		}
	}

}
