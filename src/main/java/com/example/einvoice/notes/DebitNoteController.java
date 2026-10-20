package com.example.einvoice.notes;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.InvoiceController;
import com.example.einvoice.billing.InvoiceController.IssuedResponse;
import com.example.einvoice.billing.InvoiceService.PaymentCommand;
import com.example.einvoice.notes.CreditNoteController.ModifiedDocumentRequest;
import com.example.einvoice.notes.DebitNoteService.IssueDebitNoteCommand;
import com.example.einvoice.notes.DebitNoteService.ReasonCommand;
import com.example.einvoice.notes.ModifiedInvoices.WrittenInvoice;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.web.Decimals;
import com.example.einvoice.web.TextRules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/debit-notes")
public class DebitNoteController {

	private final DebitNoteService service;

	private final FiscalDocumentRepository documents;

	private final JsonMapper json;

	public DebitNoteController(DebitNoteService service, FiscalDocumentRepository documents, JsonMapper json) {
		this.service = service;
		this.documents = documents;
		this.json = json;
	}

	record ReasonRequest(@NotBlank @Size(max = 300) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String reason,
			@NotNull @Pattern(regexp = Decimals.AMOUNT) String value, @NotNull @Pattern(regexp = "[0-9]{1,4}") String ivaRateCode) {
	}

	record PaymentRequest(@NotNull @Pattern(regexp = "[0-9]{2}") String methodCode,
			@NotNull @Pattern(regexp = Decimals.AMOUNT) String amount) {
	}

	record IssueDebitNoteRequest(@NotNull UUID taxpayerId,
			@NotNull @Pattern(regexp = "[0-9]{3}") String establishmentCode,
			@NotNull @Pattern(regexp = "[0-9]{3}") String emissionPointCode, LocalDate issueDate, UUID invoiceId,
			@Valid ModifiedDocumentRequest modifiedDocument, UUID customerId,
			@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid ReasonRequest> reasons,
			@NotNull @Size(min = 1, max = 1) List<@NotNull @Valid PaymentRequest> payments) {

		IssueDebitNoteCommand toCommand() {
			PaymentRequest payment = payments.get(0);
			return new IssueDebitNoteCommand(taxpayerId, establishmentCode, emissionPointCode, issueDate, invoiceId,
					modifiedDocument == null ? null
							: new WrittenInvoice(modifiedDocument.number(), modifiedDocument.issueDate()),
					customerId,
					reasons.stream()
						.map(reason -> new ReasonCommand(reason.reason().strip(),
								Decimals.parse(reason.value(), 12, 2), reason.ivaRateCode()))
						.toList(),
					new PaymentCommand(payment.methodCode(), Decimals.parse(payment.amount(), 12, 2)));
		}

	}

	/** Acepta la nota para procesamiento: RESERVED no es todavía un comprobante autorizado. */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	IssuedResponse issue(TenantAccess access,
			@RequestHeader(value = InvoiceController.IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody IssueDebitNoteRequest request) {
		InvoiceController.requireIdempotencyKey(idempotencyKey);
		UUID id = service.issue(access, idempotencyKey, InvoiceController.requestHash(json, request),
				request.toCommand());
		// En un reintento el documento puede haber avanzado: se informa su estado actual.
		String state = documents.find(access.tenantId(), id).orElseThrow(ApiException::notFound).state();
		return IssuedResponse.of(id, state);
	}

}
