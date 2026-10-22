package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.einvoice.billing.FiscalDocumentRepository.NewLine;
import com.example.einvoice.billing.FiscalDocumentRepository.NewPayment;
import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentType;
import com.example.einvoice.xml.InvoiceXmlBuilder;
import com.example.einvoice.xml.InvoiceXmlData;
import com.example.einvoice.xml.InvoiceXmlFixtures;

/** La factura como tipo de comprobante: delega en el generador de siempre sin cambiar un byte. */
class InvoiceKindTest {

	private final InvoiceKind kind = new InvoiceKind(null);

	static DocumentEnvelope envelope(InvoiceXmlData data) {
		return new DocumentEnvelope(DocumentType.of(data.documentType()), data.environment(),
				new DocumentEnvelope.Issuer(data.issuerLegalName(), data.issuerTradeName(), data.issuerRuc(),
						data.headOfficeAddress(), data.establishmentAddress(), data.accountingRequired(),
						data.specialTaxpayerCode(), data.withholdingAgentCode(), data.rimpeRegime(), null),
				data.accessKey(), data.establishmentCode(), data.emissionPointCode(), data.sequence(),
				data.issueDate(),
				new DocumentEnvelope.Recipient(data.customerIdType(), data.customerIdNumber(), data.customerName(),
						data.customerAddress(), "cliente@example.test", null),
				new DocumentEnvelope.Totals(data.subtotal(), data.totalDiscount(), null, data.total()), null,
				data.providerRuc());
	}

	static InvoiceBody body(InvoiceXmlData data) {
		return new InvoiceBody(
				data.lines().stream()
					.map(line -> new NewLine(null, line.mainCode(), line.description(), line.quantity(),
							line.unitPrice(), line.discount(), line.subtotal(), line.taxCode(), line.taxRateCode(),
							line.taxRate(), line.taxAmount()))
					.toList(),
				data.taxes().stream()
					.map(tax -> new TaxTotal(tax.taxCode(), tax.rateCode(), tax.rate(), tax.base(), tax.amount()))
					.toList(),
				data.payments().stream()
					.map(payment -> new NewPayment(payment.methodCode(), payment.amount(), payment.term(),
							payment.timeUnit()))
					.toList());
	}

	@Test
	void xmlIsTheSameAsTheInvoiceBuilder() {
		InvoiceXmlData data = InvoiceXmlFixtures.standard();
		assertThat(kind.buildXml(envelope(data), body(data))).isEqualTo(InvoiceXmlBuilder.build(data));
	}

	@Test
	void xmlKeepsTheOptionalIssuerFields() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.withholdingAgentCode = "1";
		builder.specialTaxpayerCode = "5368";
		builder.accountingRequired = true;
		builder.rimpeRegime = "EMPRENDEDOR";
		builder.providerRuc = "1790099999001";
		InvoiceXmlData data = builder.build();
		assertThat(kind.buildXml(envelope(data), body(data))).isEqualTo(InvoiceXmlBuilder.build(data));
	}

	@Test
	void namesAndSchema() {
		assertThat(kind.type()).isEqualTo(DocumentType.INVOICE);
		assertThat(kind.schema()).isEqualTo(DocumentSchema.INVOICE_1_1_0);
		assertThat(kind.displayName()).isEqualTo("Factura");
		assertThat(kind.fileStem()).isEqualTo("factura");
		assertThat(kind.auditReserved()).isEqualTo("INVOICE_RESERVED");
		assertThat(kind.contentJson(body(InvoiceXmlFixtures.standard()))).isNull();
	}

	@Test
	void numberIsEstablishmentPointAndPaddedSequence() {
		assertThat(envelope(InvoiceXmlFixtures.standard()).number()).isEqualTo("001-002-000000123");
	}

}
