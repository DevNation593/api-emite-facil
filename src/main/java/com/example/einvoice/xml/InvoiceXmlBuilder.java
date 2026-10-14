package com.example.einvoice.xml;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Escribe el XML de una factura en la versión 1.1.0 (ficha técnica v2.34, Anexo 3), que es la que
 * admite seis decimales en cantidad y precio unitario. El resultado es determinista: los mismos
 * datos producen los mismos bytes, sin espacios entre elementos, listos para firmar.
 */
public final class InvoiceXmlBuilder {

	public static final SchemaVersion VERSION = SchemaVersion.V1_1_0;

	/** Tipo de emisión normal: el único del esquema off-line. */
	private static final String NORMAL_EMISSION = "1";

	private static final String CURRENCY = "DOLAR";

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final StringBuilder out = new StringBuilder(4096);

	private InvoiceXmlBuilder() {
	}

	public static byte[] build(InvoiceXmlData data) {
		InvoiceXmlBuilder builder = new InvoiceXmlBuilder();
		builder.write(data);
		return builder.out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private void write(InvoiceXmlData d) {
		out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
		out.append("<factura id=\"comprobante\" version=\"").append(VERSION.value()).append("\">");

		open("infoTributaria");
		element("ambiente", d.environment());
		element("tipoEmision", NORMAL_EMISSION);
		element("razonSocial", d.issuerLegalName());
		optional("nombreComercial", d.issuerTradeName());
		element("ruc", d.issuerRuc());
		element("claveAcceso", d.accessKey());
		element("codDoc", d.documentType());
		element("estab", d.establishmentCode());
		element("ptoEmi", d.emissionPointCode());
		element("secuencial", String.format(Locale.ROOT, "%09d", d.sequence()));
		element("dirMatriz", d.headOfficeAddress());
		optional("agenteRetencion", d.withholdingAgentCode());
		optional("contribuyenteRimpe", rimpeLegend(d.rimpeRegime()));
		close("infoTributaria");

		open("infoFactura");
		element("fechaEmision", d.issueDate().format(DATE));
		optional("dirEstablecimiento", d.establishmentAddress());
		optional("contribuyenteEspecial", d.specialTaxpayerCode());
		element("obligadoContabilidad", d.accountingRequired() ? "SI" : "NO");
		element("tipoIdentificacionComprador", d.customerIdType());
		element("razonSocialComprador", d.customerName());
		element("identificacionComprador", d.customerIdNumber());
		optional("direccionComprador", d.customerAddress());
		element("totalSinImpuestos", money(d.subtotal()));
		element("totalDescuento", money(d.totalDiscount()));
		open("totalConImpuestos");
		for (InvoiceXmlData.Tax tax : d.taxes()) {
			open("totalImpuesto");
			element("codigo", tax.taxCode());
			element("codigoPorcentaje", tax.rateCode());
			element("baseImponible", money(tax.base()));
			element("valor", money(tax.amount()));
			close("totalImpuesto");
		}
		close("totalConImpuestos");
		element("propina", money(BigDecimal.ZERO));
		element("importeTotal", money(d.total()));
		element("moneda", CURRENCY);
		open("pagos");
		for (InvoiceXmlData.Payment payment : d.payments()) {
			open("pago");
			element("formaPago", payment.methodCode());
			element("total", money(payment.amount()));
			close("pago");
		}
		close("pagos");
		close("infoFactura");

		open("detalles");
		for (InvoiceXmlData.Line line : d.lines()) {
			open("detalle");
			element("codigoPrincipal", line.mainCode());
			element("descripcion", line.description());
			element("cantidad", scaled(line.quantity(), 6));
			element("precioUnitario", scaled(line.unitPrice(), 6));
			element("descuento", money(line.discount()));
			element("precioTotalSinImpuesto", money(line.subtotal()));
			open("impuestos");
			open("impuesto");
			element("codigo", line.taxCode());
			element("codigoPorcentaje", line.taxRateCode());
			element("tarifa", money(line.taxRate()));
			element("baseImponible", money(line.subtotal()));
			element("valor", money(line.taxAmount()));
			close("impuesto");
			close("impuestos");
			close("detalle");
		}
		close("detalles");

		if (present(d.providerRuc())) {
			// Ficha técnica v2.34, Anexo 26: RUC del proveedor del sistema de facturación.
			open("infoAdicional");
			out.append("<campoAdicional nombre=\"RUC Proveedor\">");
			text(d.providerRuc());
			out.append("</campoAdicional>");
			close("infoAdicional");
		}
		out.append("</factura>");
	}

	/** Leyendas del Anexo 22 de la ficha. */
	private static String rimpeLegend(String regime) {
		return switch (regime) {
			case "NONE" -> null;
			case "EMPRENDEDOR" -> "CONTRIBUYENTE RÉGIMEN RIMPE";
			case "NEGOCIO_POPULAR" -> "CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE";
			default -> throw new IllegalArgumentException("Régimen RIMPE desconocido: " + regime);
		};
	}

	private void open(String name) {
		out.append('<').append(name).append('>');
	}

	private void close(String name) {
		out.append("</").append(name).append('>');
	}

	private void element(String name, String value) {
		open(name);
		text(value);
		close(name);
	}

	private void optional(String name, String value) {
		if (present(value)) {
			element(name, value);
		}
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

	/** Escapa el texto y rechaza los caracteres que XML 1.0 no admite. */
	private void text(String value) {
		value.codePoints().forEach(c -> {
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&apos;");
				default -> {
					if (!allowedInXml(c)) {
						throw new IllegalArgumentException("Carácter no admitido en XML: U+" + Integer.toHexString(c));
					}
					out.appendCodePoint(c);
				}
			}
		});
	}

	private static boolean allowedInXml(int c) {
		return c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)
				|| (c >= 0x10000 && c <= 0x10FFFF);
	}

	private static String money(BigDecimal value) {
		return scaled(value, 2);
	}

	private static String scaled(BigDecimal value, int scale) {
		return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
	}

}
