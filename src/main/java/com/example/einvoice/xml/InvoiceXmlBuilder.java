package com.example.einvoice.xml;

import java.math.BigDecimal;

/**
 * Escribe el XML de una factura en la versión 1.1.0 (ficha técnica v2.34, Anexo 3), que es la que
 * admite seis decimales en cantidad y precio unitario. El resultado es determinista: los mismos
 * datos producen los mismos bytes, sin espacios entre elementos, listos para firmar.
 */
public final class InvoiceXmlBuilder {

	public static final SchemaVersion VERSION = SchemaVersion.V1_1_0;

	private InvoiceXmlBuilder() {
	}

	public static byte[] build(InvoiceXmlData d) {
		XmlWriter w = new XmlWriter();
		w.openRoot("factura", VERSION.value());
		w.infoTributaria(d.environment(), d.issuerLegalName(), d.issuerTradeName(), d.issuerRuc(),
				d.accessKey(), d.documentType(), d.establishmentCode(), d.emissionPointCode(), d.sequence(),
				d.headOfficeAddress(), d.withholdingAgentCode(), d.rimpeRegime());

		w.open("infoFactura");
		w.date("fechaEmision", d.issueDate());
		w.optional("dirEstablecimiento", d.establishmentAddress());
		w.optional("contribuyenteEspecial", d.specialTaxpayerCode());
		w.element("obligadoContabilidad", d.accountingRequired() ? "SI" : "NO");
		w.element("tipoIdentificacionComprador", d.customerIdType());
		w.element("razonSocialComprador", d.customerName());
		w.element("identificacionComprador", d.customerIdNumber());
		w.optional("direccionComprador", d.customerAddress());
		w.money("totalSinImpuestos", d.subtotal());
		w.money("totalDescuento", d.totalDiscount());
		w.open("totalConImpuestos");
		for (InvoiceXmlData.Tax tax : d.taxes()) {
			w.open("totalImpuesto");
			w.element("codigo", tax.taxCode());
			w.element("codigoPorcentaje", tax.rateCode());
			w.money("baseImponible", tax.base());
			w.money("valor", tax.amount());
			w.close("totalImpuesto");
		}
		w.close("totalConImpuestos");
		w.money("propina", BigDecimal.ZERO);
		w.money("importeTotal", d.total());
		w.element("moneda", XmlWriter.CURRENCY);
		w.open("pagos");
		for (InvoiceXmlData.Payment payment : d.payments()) {
			w.open("pago");
			w.element("formaPago", payment.methodCode());
			w.money("total", payment.amount());
			if (payment.term() != null) {
				w.money("plazo", payment.term());
				w.element("unidadTiempo", payment.timeUnit());
			}
			w.close("pago");
		}
		w.close("pagos");
		w.close("infoFactura");

		w.open("detalles");
		for (InvoiceXmlData.Line line : d.lines()) {
			w.open("detalle");
			w.element("codigoPrincipal", line.mainCode());
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

		w.providerInfo(d.providerRuc());
		w.close("factura");
		return w.toBytes();
	}

}
