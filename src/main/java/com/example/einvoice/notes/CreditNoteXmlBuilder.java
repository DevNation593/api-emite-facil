package com.example.einvoice.notes;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.xml.XmlWriter;

/**
 * Escribe el XML de una nota de crédito en la versión 1.1.0 (XSD NotaCredito_V1.1.0). Igual que la
 * factura, es determinista y sin espacios entre elementos, listo para firmar.
 */
public final class CreditNoteXmlBuilder {

	public static final String VERSION = "1.1.0";

	private CreditNoteXmlBuilder() {
	}

	public static byte[] build(DocumentEnvelope envelope, CreditNoteContent content) {
		DocumentEnvelope.Issuer issuer = envelope.issuer();
		DocumentEnvelope.Recipient recipient = envelope.recipient();
		DocumentEnvelope.Totals totals = envelope.totals();
		DocumentEnvelope.ModifiedDocument modified = envelope.modified();

		XmlWriter w = new XmlWriter();
		w.openRoot("notaCredito", VERSION);
		w.infoTributaria(envelope.environment(), issuer.legalName(), issuer.tradeName(), issuer.ruc(),
				envelope.accessKey(), envelope.type().code(), envelope.establishmentCode(),
				envelope.emissionPointCode(), envelope.sequence(), issuer.headOfficeAddress(),
				issuer.withholdingAgentCode(), issuer.rimpeRegime());

		w.open("infoNotaCredito");
		w.date("fechaEmision", envelope.issueDate());
		w.optional("dirEstablecimiento", issuer.establishmentAddress());
		w.element("tipoIdentificacionComprador", recipient.idType());
		w.element("razonSocialComprador", recipient.name());
		w.element("identificacionComprador", recipient.idNumber());
		w.optional("contribuyenteEspecial", issuer.specialTaxpayerCode());
		w.element("obligadoContabilidad", issuer.accountingRequired() ? "SI" : "NO");
		w.element("codDocModificado", modified.code());
		w.element("numDocModificado", modified.number());
		w.date("fechaEmisionDocSustento", modified.issueDate());
		w.money("totalSinImpuestos", totals.subtotal());
		w.money("valorModificacion", totals.total());
		w.element("moneda", XmlWriter.CURRENCY);
		w.open("totalConImpuestos");
		for (NoteTax tax : content.taxes()) {
			w.open("totalImpuesto");
			w.element("codigo", tax.taxCode());
			w.element("codigoPorcentaje", tax.rateCode());
			w.money("baseImponible", tax.base());
			w.money("valor", tax.amount());
			w.close("totalImpuesto");
		}
		w.close("totalConImpuestos");
		w.element("motivo", content.reason());
		w.close("infoNotaCredito");

		w.open("detalles");
		for (CreditNoteContent.Line line : content.lines()) {
			w.open("detalle");
			w.element("codigoInterno", line.mainCode());
			w.element("descripcion", line.description());
			w.scaled("cantidad", line.quantity(), 6);
			w.scaled("precioUnitario", line.unitPrice(), 6);
			w.money("descuento", line.discount());
			w.money("precioTotalSinImpuesto", line.subtotal());
			w.open("impuestos");
			w.open("impuesto");
			w.element("codigo", line.taxCode());
			w.element("codigoPorcentaje", line.taxRateCode());
			w.money("tarifa", line.taxRate());
			w.money("baseImponible", line.subtotal());
			w.money("valor", line.taxAmount());
			w.close("impuesto");
			w.close("impuestos");
			w.close("detalle");
		}
		w.close("detalles");

		w.providerInfo(envelope.providerRuc());
		w.close("notaCredito");
		return w.toBytes();
	}

}
