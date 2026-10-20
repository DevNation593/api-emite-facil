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
import com.example.einvoice.notes.CreditNoteService.CreditLineCommand;
import com.example.einvoice.notes.CreditNoteService.IssueCreditNoteCommand;
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
@RequestMapping("/api/v1/credit-notes")
public class CreditNoteController {

	private final CreditNoteService service;

	private final FiscalDocumentRepository documents;

	private final JsonMapper json;

	public CreditNoteController(CreditNoteService service, FiscalDocumentRepository documents, JsonMapper json) {
		this.service = service;
		this.documents = documents;
		this.json = json;
	}

	/** La factura escrita a mano que modifica la nota: siempre una factura (código 01). */
	record ModifiedDocumentRequest(@NotNull @Pattern(regexp = "01") String code,
			@NotNull @Pattern(regexp = "[0-9]{3}-[0-9]{3}-[0-9]{9}") String number, @NotNull LocalDate issueDate) {
	}

	record CreditLineRequest(Integer invoiceLineNo, UUID productId,
			@NotNull @Pattern(regexp = Decimals.QUANTITY_OR_PRICE) String quantity,
			@Pattern(regexp = Decimals.QUANTITY_OR_PRICE) String unitPrice,
			@Pattern(regexp = Decimals.AMOUNT) String discount) {
	}

	record IssueCreditNoteRequest(@NotNull UUID taxpayerId,
			@NotNull @Pattern(regexp = "[0-9]{3}") String establishmentCode,
			@NotNull @Pattern(regexp = "[0-9]{3}") String emissionPointCode, LocalDate issueDate,
			@NotBlank @Size(max = 300) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String reason, UUID invoiceId,
			@Valid ModifiedDocumentRequest modifiedDocument, UUID customerId,
			@NotNull @Size(min = 1, max = 200) List<@NotNull @Valid CreditLineRequest> lines) {

		IssueCreditNoteCommand toCommand() {
			return new IssueCreditNoteCommand(taxpayerId, establishmentCode, emissionPointCode, issueDate,
					reason.strip(), invoiceId,
					modifiedDocument == null ? null
							: new WrittenInvoice(modifiedDocument.number(), modifiedDocument.issueDate()),
					customerId,
					lines.stream()
						.map(line -> new CreditLineCommand(line.invoiceLineNo(), line.productId(),
								Decimals.parse(line.quantity(), 12, 6),
								line.unitPrice() == null ? null : Decimals.parse(line.unitPrice(), 12, 6),
								line.discount() == null ? null : Decimals.parse(line.discount(), 12, 2)))
						.toList());
		}

	}

	/** Acepta la nota para procesamiento: RESERVED no es todavía un comprobante autorizado. */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	IssuedResponse issue(TenantAccess access,
			@RequestHeader(value = InvoiceController.IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody IssueCreditNoteRequest request) {
		InvoiceController.requireIdempotencyKey(idempotencyKey);
		UUID id = service.issue(access, idempotencyKey, InvoiceController.requestHash(json, request),
				request.toCommand());
		// En un reintento el documento puede haber avanzado: se informa su estado actual.
		String state = documents.find(access.tenantId(), id).orElseThrow(ApiException::notFound).state();
		return IssuedResponse.of(id, state);
	}

}
