package com.example.einvoice.notes;

import org.springframework.stereotype.Component;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.JsonContentKind;
import com.example.einvoice.billing.RideEnvelope;
import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentType;

/** La nota de débito como tipo de comprobante: su contenido viaja como JSON en el sobre. */
@Component
public class DebitNoteKind extends JsonContentKind<DebitNoteContent> {

	private final FiscalDocumentRepository documents;

	public DebitNoteKind(FiscalDocumentRepository documents) {
		super(DebitNoteContent.class, documents);
		this.documents = documents;
	}

	@Override
	public DocumentType type() {
		return DocumentType.DEBIT_NOTE;
	}

	@Override
	public DocumentSchema schema() {
		return DocumentSchema.DEBIT_NOTE_1_0_0;
	}

	@Override
	public String displayName() {
		return "Nota de débito";
	}

	@Override
	public String fileStem() {
		return "nota-debito";
	}

	@Override
	public String auditReserved() {
		return "DEBIT_NOTE_RESERVED";
	}

	@Override
	public byte[] buildXml(DocumentEnvelope envelope, DebitNoteContent content) {
		return DebitNoteXmlBuilder.build(envelope, content);
	}

	@Override
	public byte[] buildRide(RideEnvelope ride, DebitNoteContent content) {
		return DebitNoteRide.build(ride, content, documents.paymentMethodNames());
	}

}
