package com.example.einvoice.notes;

import org.springframework.stereotype.Component;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.JsonContentKind;
import com.example.einvoice.billing.RideEnvelope;
import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentType;

/** La nota de crédito como tipo de comprobante: su contenido viaja como JSON en el sobre. */
@Component
public class CreditNoteKind extends JsonContentKind<CreditNoteContent> {

	public CreditNoteKind(FiscalDocumentRepository documents) {
		super(CreditNoteContent.class, documents);
	}

	@Override
	public DocumentType type() {
		return DocumentType.CREDIT_NOTE;
	}

	@Override
	public DocumentSchema schema() {
		return DocumentSchema.CREDIT_NOTE_1_1_0;
	}

	@Override
	public String displayName() {
		return "Nota de crédito";
	}

	@Override
	public String fileStem() {
		return "nota-credito";
	}

	@Override
	public String auditReserved() {
		return "CREDIT_NOTE_RESERVED";
	}

	@Override
	public byte[] buildXml(DocumentEnvelope envelope, CreditNoteContent content) {
		return CreditNoteXmlBuilder.build(envelope, content);
	}

	@Override
	public byte[] buildRide(RideEnvelope ride, CreditNoteContent content) {
		return CreditNoteRide.build(ride, content);
	}

}
