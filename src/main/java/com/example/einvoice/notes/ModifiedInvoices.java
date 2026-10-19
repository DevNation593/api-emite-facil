package com.example.einvoice.notes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.FiscalDocumentRepository.DocumentRow;
import com.example.einvoice.billing.FiscalDocumentRepository.LineRow;
import com.example.einvoice.billing.FiscalDocumentRepository.ProcessingState;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.xml.DocumentType;

/** La factura de la plataforma que modifica una nota: se busca en la empresa, se valida y se bloquea. */
@Component
public class ModifiedInvoices {

	private static final String AUTHORIZED = "AUTHORIZED";

	/**
	 * Lo que la nota toma de la factura: su número (EEE-PPP-SSSSSSSSS), su fecha, su total, su comprador y
	 * sus líneas en orden.
	 */
	public record ModifiedInvoice(UUID id, String number, LocalDate issueDate, BigDecimal total, UUID customerId,
			List<LineRow> lines) {

		public ModifiedInvoice {
			lines = List.copyOf(lines);
		}

		/** La línea de la factura con ese número, si existe. */
		public Optional<LineRow> line(int lineNo) {
			return lines.stream().filter(line -> line.lineNo() == lineNo).findFirst();
		}

		/** El documento modificado del sobre de la nota: enlazado a la factura, con su número y su fecha. */
		public DocumentEnvelope.ModifiedDocument modifiedDocument() {
			return new DocumentEnvelope.ModifiedDocument(id, DocumentType.INVOICE.code(), number, issueDate);
		}

	}

	/** Una factura escrita a mano: su número (EEE-PPP-SSSSSSSSS) y su fecha; no está en la plataforma. */
	public record WrittenInvoice(String number, LocalDate issueDate) {

		/** El documento modificado del sobre de la nota, sin enlace. */
		public DocumentEnvelope.ModifiedDocument modifiedDocument() {
			return new DocumentEnvelope.ModifiedDocument(null, DocumentType.INVOICE.code(), number, issueDate);
		}

	}

	private final FiscalDocumentRepository documents;

	public ModifiedInvoices(FiscalDocumentRepository documents) {
		this.documents = documents;
	}

	/**
	 * Bloquea la factura con FOR UPDATE hasta el final de la transacción de la emisión: dos notas sobre la misma
	 * factura se deciden de una en una, y la segunda ve el saldo que dejó la primera. Debe correr antes de
	 * reservar el secuencial. Ajena o inexistente responde 404; si no es una factura, 422
	 * MODIFIED_DOCUMENT_NOT_INVOICE; de otro emisor de la empresa, 422 MODIFIED_INVOICE_OTHER_ISSUER; no
	 * autorizada, 422 MODIFIED_INVOICE_NOT_AUTHORIZED.
	 */
	public ModifiedInvoice lockForNote(TenantAccess access, UUID invoiceId, UUID taxpayerId) {
		ProcessingState locked = documents.lockForProcessing(access.tenantId(), invoiceId)
			.orElseThrow(ApiException::notFound);
		if (!DocumentType.INVOICE.code().equals(locked.documentType())) {
			throw unprocessable("MODIFIED_DOCUMENT_NOT_INVOICE", "El comprobante que se modifica no es una factura");
		}
		if (!locked.taxpayerId().equals(taxpayerId)) {
			throw unprocessable("MODIFIED_INVOICE_OTHER_ISSUER", "La factura es de otro emisor de la empresa");
		}
		if (!AUTHORIZED.equals(locked.state())) {
			throw unprocessable("MODIFIED_INVOICE_NOT_AUTHORIZED", "Solo se modifica una factura autorizada por el SRI");
		}
		DocumentRow invoice = documents.find(access.tenantId(), invoiceId).orElseThrow(ApiException::notFound);
		UUID customerId = documents.links(access.tenantId(), invoiceId).customerId();
		String number = invoice.establishmentCode() + "-" + invoice.emissionPointCode() + "-"
				+ "%09d".formatted(invoice.sequence());
		return new ModifiedInvoice(invoiceId, number, invoice.issueDate(), invoice.total(), customerId,
				documents.lines(access.tenantId(), invoiceId));
	}

	/**
	 * Una nota modifica una factura de la plataforma (invoiceId, sin customerId: el comprador es el de la factura)
	 * o una escrita a mano (modifiedDocument con customerId); nunca las dos ni ninguna: 400
	 * MODIFIED_DOCUMENT_REQUIRED.
	 */
	public static void requireOneModifiedDocument(UUID invoiceId, WrittenInvoice written, UUID customerId) {
		boolean fromInvoice = invoiceId != null && written == null && customerId == null;
		boolean byHand = invoiceId == null && written != null && customerId != null;
		if (!fromInvoice && !byHand) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MODIFIED_DOCUMENT_REQUIRED",
					"La nota lleva invoiceId, o modifiedDocument con customerId; no las dos cosas ni ninguna");
		}
	}

	/** 422 INVALID_MODIFIED_DOCUMENT_DATE si la factura modificada es posterior a la nota. */
	public static void requireNotLater(LocalDate modifiedDate, LocalDate noteDate) {
		if (modifiedDate.isAfter(noteDate)) {
			throw unprocessable("INVALID_MODIFIED_DOCUMENT_DATE",
					"La fecha del comprobante que se modifica no puede ser posterior a la de la nota");
		}
	}

	private static ApiException unprocessable(String errorCode, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, detail);
	}

}
