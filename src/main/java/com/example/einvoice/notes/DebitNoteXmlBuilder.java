package com.example.einvoice.notes;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.xml.XmlWriter;

/**
 * Escribe el XML de una nota de débito en la versión 1.0.0 (XSD NotaDebito_V1.0.0). Igual que la
 * factura, es determinista y sin espacios entre elementos, listo para firmar.
 */
public final class DebitNoteXmlBuilder {

	public static final String VERSION = "1.0.0";

	private DebitNoteXmlBuilder() {
	}

	public static byte[] build(DocumentEnvelope envelope, DebitNoteContent content) {
		DocumentEnvelope.Issuer issuer = envelope.issuer();
		DocumentEnvelope.Recipient recipient = envelope.recipient();
		DocumentEnvelope.Totals totals = envelope.totals();
		DocumentEnvelope.ModifiedDocument modified = envelope.modified();

		XmlWriter w = new XmlWriter();
		w.openRoot("notaDebito", VERSION);
		w.infoTributaria(envelope.environment(), issuer.legalName(), issuer.tradeName(), issuer.ruc(),
				envelope.accessKey(), envelope.type().code(), envelope.establishmentCode(),
				envelope.emissionPointCode(), envelope.sequence(), issuer.headOfficeAddress(),
				issuer.withholdingAgentCode(), issuer.rimpeRegime());

		w.open("infoNotaDebito");
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
		w.open("impuestos");
		for (NoteTax tax : content.taxes()) {
			w.open("impuesto");
			w.element("codigo", tax.taxCode());
			w.element("codigoPorcentaje", tax.rateCode());
			w.money("tarifa", tax.rate());
			w.money("baseImponible", tax.base());
			w.money("valor", tax.amount());
			w.close("impuesto");
		}
		w.close("impuestos");
		w.money("valorTotal", totals.total());
		w.open("pagos");
		for (DebitNoteContent.Payment payment : content.payments()) {
			w.open("pago");
			w.element("formaPago", payment.methodCode());
			w.money("total", payment.amount());
			w.close("pago");
		}
		w.close("pagos");
		w.close("infoNotaDebito");

		w.open("motivos");
		for (DebitNoteContent.Reason reason : content.reasons()) {
			w.open("motivo");
			w.element("razon", reason.reason());
			w.money("valor", reason.value());
			w.close("motivo");
		}
		w.close("motivos");

		w.providerInfo(envelope.providerRuc());
		w.close("notaDebito");
		return w.toBytes();
	}

}
