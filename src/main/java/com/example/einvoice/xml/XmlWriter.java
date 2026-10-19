package com.example.einvoice.xml;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Escritor de XML compartido por los generadores de comprobantes: texto escapado, sin espacios entre
 * elementos, fechas {@code dd/MM/yyyy} y decimales con escala fija, de modo que los mismos datos
 * produzcan siempre los mismos bytes.
 */
public final class XmlWriter {

	/** Tipo de emisión normal: el único del esquema off-line. */
	public static final String NORMAL_EMISSION = "1";

	public static final String CURRENCY = "DOLAR";

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final StringBuilder out = new StringBuilder(4096);

	public XmlWriter() {
		out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
	}

	public byte[] toBytes() {
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** Abre el elemento raíz con {@code id="comprobante"} y la versión del esquema. */
	public void openRoot(String name, String version) {
		out.append('<').append(name).append(" id=\"comprobante\" version=\"").append(version).append("\">");
	}

	/** {@code infoTributaria} común a todos los comprobantes (Anexo 22 para la leyenda RIMPE). */
	public void infoTributaria(String environment, String legalName, String tradeName, String ruc,
			String accessKey, String documentCode, String establishmentCode, String emissionPointCode,
			int sequence, String headOfficeAddress, String withholdingAgentCode, String rimpeRegime) {
		open("infoTributaria");
		element("ambiente", environment);
		element("tipoEmision", NORMAL_EMISSION);
		element("razonSocial", legalName);
		optional("nombreComercial", tradeName);
		element("ruc", ruc);
		element("claveAcceso", accessKey);
		element("codDoc", documentCode);
		element("estab", establishmentCode);
		element("ptoEmi", emissionPointCode);
		element("secuencial", String.format(Locale.ROOT, "%09d", sequence));
		element("dirMatriz", headOfficeAddress);
		optional("agenteRetencion", withholdingAgentCode);
		optional("contribuyenteRimpe", rimpeLegend(rimpeRegime));
		close("infoTributaria");
	}

	/** Ficha técnica v2.34, Anexo 26: RUC del proveedor del sistema de facturación. */
	public void providerInfo(String providerRuc) {
		if (present(providerRuc)) {
			open("infoAdicional");
			out.append("<campoAdicional nombre=\"RUC Proveedor\">");
			text(providerRuc);
			out.append("</campoAdicional>");
			close("infoAdicional");
		}
	}

	/** Leyendas del Anexo 22 de la ficha. */
	static String rimpeLegend(String regime) {
		return switch (regime) {
			case "NONE" -> null;
			case "EMPRENDEDOR" -> "CONTRIBUYENTE RÉGIMEN RIMPE";
			case "NEGOCIO_POPULAR" -> "CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE";
			default -> throw new IllegalArgumentException("Régimen RIMPE desconocido: " + regime);
		};
	}

	public void open(String name) {
		out.append('<').append(name).append('>');
	}

	public void close(String name) {
		out.append("</").append(name).append('>');
	}

	public void element(String name, String value) {
		open(name);
		text(value);
		close(name);
	}

	public void optional(String name, String value) {
		if (present(value)) {
			element(name, value);
		}
	}

	public void date(String name, LocalDate value) {
		element(name, value.format(DATE));
	}

	/** Importe con dos decimales. */
	public void money(String name, BigDecimal value) {
		element(name, money(value));
	}

	/** Número con la escala dada (seis para cantidad y precio unitario). */
	public void scaled(String name, BigDecimal value, int scale) {
		element(name, scaled(value, scale));
	}

	public static boolean present(String value) {
		return value != null && !value.isBlank();
	}

	public static String money(BigDecimal value) {
		return scaled(value, 2);
	}

	public static String scaled(BigDecimal value, int scale) {
		return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
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

}
