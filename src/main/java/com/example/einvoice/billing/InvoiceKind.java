package com.example.einvoice.billing;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.example.einvoice.delivery.RideData;
import com.example.einvoice.delivery.RidePdfBuilder;
import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentType;
import com.example.einvoice.xml.InvoiceXmlBuilder;
import com.example.einvoice.xml.InvoiceXmlData;

/**
 * La factura como tipo de comprobante. Su contenido vive en las tablas de líneas, impuestos y pagos, no
 * en content; el XML y el RIDE salen de los generadores de siempre, sin cambiar un byte.
 */
@Component
public class InvoiceKind implements DocumentKind<InvoiceBody> {

	private final FiscalDocumentRepository documents;

	public InvoiceKind(FiscalDocumentRepository documents) {
		this.documents = documents;
	}

	@Override
	public DocumentType type() {
		return DocumentType.INVOICE;
	}

	@Override
	public DocumentSchema schema() {
		return InvoiceXmlBuilder.VERSION.schema();
	}

	@Override
	public String displayName() {
		return "Factura";
	}

	@Override
	public String fileStem() {
		return "factura";
	}

	@Override
	public String auditReserved() {
		return "INVOICE_RESERVED";
	}

	@Override
	public String contentJson(InvoiceBody content) {
		return null;
	}

	@Override
	public void saveDetails(UUID tenantId, UUID documentId, InvoiceBody content) {
		documents.insertInvoiceBody(tenantId, documentId, content);
	}

	@Override
	public InvoiceBody loadContent(UUID tenantId, UUID documentId) {
		return documents.invoiceBody(tenantId, documentId);
	}

	@Override
	public byte[] buildXml(DocumentEnvelope envelope, InvoiceBody content) {
		return InvoiceXmlBuilder.build(xmlData(envelope, content));
	}

	@Override
	public byte[] buildRide(RideEnvelope ride, InvoiceBody content) {
		DocumentEnvelope envelope = ride.envelope();
		return RidePdfBuilder.build(new RideData(xmlData(envelope, content), ride.authorizationNumber(),
				ride.authorizedAt(), envelope.recipient().email(), envelope.recipient().phone(),
				envelope.issuer().phone(), documents.paymentMethodNames()));
	}

	/** Junta el sobre y el cuerpo en los datos que consume el generador del XML de la factura. */
	static InvoiceXmlData xmlData(DocumentEnvelope envelope, InvoiceBody body) {
		DocumentEnvelope.Issuer issuer = envelope.issuer();
		DocumentEnvelope.Recipient recipient = envelope.recipient();
		DocumentEnvelope.Totals totals = envelope.totals();
		return new InvoiceXmlData(envelope.environment(), issuer.legalName(), issuer.tradeName(), issuer.ruc(),
				envelope.accessKey(), envelope.type().code(), envelope.establishmentCode(),
				envelope.emissionPointCode(), envelope.sequence(), issuer.headOfficeAddress(),
				issuer.withholdingAgentCode(), issuer.rimpeRegime(), envelope.issueDate(),
				issuer.establishmentAddress(), issuer.specialTaxpayerCode(), issuer.accountingRequired(),
				recipient.idType(), recipient.name(), recipient.idNumber(), recipient.address(), totals.subtotal(),
				totals.totalDiscount(),
				body.taxes().stream()
					.map(tax -> new InvoiceXmlData.Tax(tax.taxCode(), tax.rateCode(), tax.rate(), tax.base(),
							tax.amount()))
					.toList(),
				totals.total(),
				body.lines().stream()
					.map(line -> new InvoiceXmlData.Line(line.mainCode(), line.description(), line.quantity(),
							line.unitPrice(), line.discount(), line.subtotal(), line.taxCode(), line.taxRateCode(),
							line.taxRate(), line.taxAmount()))
					.toList(),
				body.payments().stream()
					.map(payment -> new InvoiceXmlData.Payment(payment.methodCode(), payment.amount(), payment.term(),
							payment.timeUnit()))
					.toList(),
				envelope.providerRuc());
	}

}
