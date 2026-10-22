package com.example.einvoice.delivery;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.Barcode128;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.notes.NoteTax;

/**
 * Piezas comunes del RIDE: fuentes y colores, cabecera con emisor, RUC, título, número, autorización y
 * código de barras de la clave de acceso, bloques con franja azul de título (cliente, información
 * adicional), detalle, totales y pie. La factura y las notas las componen; el título y el número del
 * documento son parámetros. Usa Helvetica, así que los caracteres que esa fuente no tiene (fuera de
 * CP1252) se omiten.
 */
public final class RideLayout {

	/** Hora de Ecuador continental. */
	private static final ZoneOffset ECUADOR = ZoneOffset.ofHours(-5);

	public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

	private static final Charset CP1252 = Charset.forName("windows-1252");

	/** Azul de las franjas de título y de las etiquetas de los totales. */
	private static final Color NAVY = new Color(40, 54, 127);

	/** Fondo de las filas de datos. */
	private static final Color PALE = new Color(238, 242, 245);

	/** Fondo de la franja con el tipo de comprobante. */
	private static final Color BAND_GREY = new Color(224, 228, 234);

	/** Raya fina bajo cada fila del detalle. */
	private static final Color RULE = new Color(214, 219, 226);

	public static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);

	public static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

	public static final Font NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 8);

	public static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7);

	private static final Font NAME = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, Font.NORMAL, NAVY);

	private static final Font RUC = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Font.NORMAL, Color.WHITE);

	private static final Font ON_NAVY = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, Color.WHITE);

	private static final Font AMOUNT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);

	private static final Font GRAND_AMOUNT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);

	private RideLayout() {
	}

	/** Documento A4 con los márgenes del RIDE. */
	public static Document newDocument() {
		return new Document(PageSize.A4, 28, 28, 28, 28);
	}

	/**
	 * Cabecera: a la izquierda el nombre del emisor en grande y sus datos; a la derecha la franja del
	 * RUC, el tipo y número de comprobante, la autorización y la clave de acceso con su código de barras.
	 */
	public static PdfPTable header(DocumentEnvelope.Issuer issuer, String title, String number, String environment,
			String accessKey, String authorizationNumber, OffsetDateTime authorizedAt, PdfWriter writer) {
		PdfPTable issuerBox = box();
		PdfPCell name = centered(issuer.tradeName() != null ? issuer.tradeName() : issuer.legalName(), NAME);
		name.setPaddingTop(14);
		name.setPaddingBottom(14);
		issuerBox.addCell(name);
		issuerBox.addCell(labelled("RAZÓN SOCIAL: ", issuer.legalName()));
		if (issuer.tradeName() != null) {
			issuerBox.addCell(labelled("NOMBRE COMERCIAL: ", issuer.tradeName()));
		}
		issuerBox.addCell(labelled("DIRECCIÓN MATRIZ: ", issuer.headOfficeAddress()));
		issuerBox.addCell(labelled("DIRECCIÓN SUCURSAL: ", issuer.establishmentAddress()));
		if (issuer.specialTaxpayerCode() != null) {
			issuerBox.addCell(labelled("CONTRIBUYENTE ESPECIAL NRO: ", issuer.specialTaxpayerCode()));
		}
		issuerBox.addCell(labelled("OBLIGADO A LLEVAR CONTABILIDAD: ", issuer.accountingRequired() ? "SI" : "NO"));
		if (issuer.withholdingAgentCode() != null) {
			issuerBox.addCell(labelled("AGENTE DE RETENCIÓN RESOLUCIÓN NO. ", issuer.withholdingAgentCode()));
		}
		String rimpe = rimpeLegend(issuer.rimpeRegime());
		if (rimpe != null) {
			issuerBox.addCell(labelled(rimpe, ""));
		}

		PdfPTable fiscal = box();
		fiscal.addCell(band("R.U.C. " + issuer.ruc(), RUC, NAVY));
		fiscal.addCell(band(title, TITLE, BAND_GREY));
		fiscal.addCell(centered(number, NORMAL));
		fiscal.addCell(centered("NÚMERO DE AUTORIZACIÓN", BOLD));
		fiscal.addCell(digits(authorizationNumber));
		fiscal.addCell(labelled("FECHA Y HORA DE AUTORIZACIÓN: ",
				DATE_TIME.format(authorizedAt.withOffsetSameInstant(ECUADOR))));
		fiscal.addCell(labelled("AMBIENTE: ", "2".equals(environment) ? "PRODUCCIÓN" : "PRUEBAS"));
		fiscal.addCell(labelled("ESTADO: ", "AUTORIZADO"));
		fiscal.addCell(labelled("EMISIÓN: ", "NORMAL"));
		fiscal.addCell(line("CLAVE DE ACCESO:", BOLD));
		fiscal.addCell(barcode(accessKey, writer));
		fiscal.addCell(digits(accessKey));

		PdfPTable header = new PdfPTable(new float[] { 49, 51 });
		header.setWidthPercentage(100);
		PdfPCell left = wrap(issuerBox);
		left.setPaddingRight(6);
		header.addCell(left);
		PdfPCell right = wrap(fiscal);
		right.setPaddingLeft(6);
		header.addCell(right);
		return header;
	}

	/** «DATOS DEL CLIENTE»: nombre, identificación según su tipo, dirección y fecha de emisión. */
	public static PdfPTable recipient(DocumentEnvelope.Recipient recipient, LocalDate issueDate) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("RAZÓN SOCIAL:", recipient.name());
		rows.put(idLabel(recipient.idType()) + ":", recipient.idNumber());
		if (recipient.address() != null) {
			rows.put("DIRECCIÓN:", recipient.address());
		}
		rows.put("FECHA DE EMISIÓN:", DATE.format(issueDate));
		return infoTable("DATOS DEL CLIENTE", rows);
	}

	/** Bloque a todo el ancho: franja azul con el título y una fila de fondo claro por etiqueta y valor. */
	public static PdfPTable infoTable(String title, Map<String, String> rows) {
		PdfPTable table = new PdfPTable(new float[] { 24, 76 });
		table.setWidthPercentage(100);
		PdfPCell band = band(title, ON_NAVY, NAVY);
		band.setColspan(2);
		table.addCell(band);
		for (Map.Entry<String, String> row : rows.entrySet()) {
			table.addCell(pale(new PdfPCell(new Phrase(clean(row.getKey()), BOLD))));
			table.addCell(pale(new PdfPCell(new Phrase(clean(row.getValue()), NORMAL))));
		}
		return table;
	}

	/** Tabla con encabezado azul que se repite en cada página; las filas las añade quien llama con {@link #text}. */
	public static PdfPTable detailTable(float[] widths, String... titles) {
		PdfPTable table = new PdfPTable(widths);
		table.setWidthPercentage(100);
		table.setHeaderRows(1);
		for (String title : titles) {
			table.addCell(band(title, ON_NAVY, NAVY));
		}
		return table;
	}

	/** Tabla de totales de dos columnas, vacía: etiqueta sobre azul y valor a la derecha. */
	public static PdfPTable totalsTable() {
		PdfPTable totals = new PdfPTable(new float[] { 64, 36 });
		totals.setWidthPercentage(100);
		return totals;
	}

	/** Filas «Subtotal IVA 15%», «Subtotal No Objeto IVA»… una por tarifa. */
	public static void subtotalRows(PdfPTable totals, List<NoteTax> taxes) {
		for (NoteTax tax : taxes) {
			total(totals, "Subtotal " + rateLabel(tax.rateCode(), tax.rate()), tax.base());
		}
	}

	/** Filas de IVA de las tarifas mayores que cero. */
	public static void vatRows(PdfPTable totals, List<NoteTax> taxes) {
		for (NoteTax tax : taxes) {
			if (tax.rate().signum() > 0) {
				total(totals, "IVA " + percent(tax.rate()), tax.amount());
			}
		}
	}

	public static void total(PdfPTable totals, String label, BigDecimal value) {
		totalRow(totals, label, value, AMOUNT);
	}

	/** Última fila de los totales, con el importe más grande. */
	public static void grandTotal(PdfPTable totals, String label, BigDecimal value) {
		totalRow(totals, label, value, GRAND_AMOUNT);
	}

	/**
	 * Cierre del RIDE, que si no cabe al final de una página pasa entero a la siguiente: los totales a la
	 * derecha; debajo y a todo el ancho, below (información adicional, formas de pago) y la leyenda del RIDE.
	 */
	public static PdfPTable footer(PdfPTable below, PdfPTable totals) {
		PdfPTable footer = new PdfPTable(new float[] { 55, 45 });
		footer.setWidthPercentage(100);
		footer.setKeepTogether(true);
		footer.addCell(gap());
		footer.addCell(wrap(totals));
		PdfPCell space = gap();
		space.setColspan(2);
		footer.addCell(space);
		PdfPCell blocks = wrap(below);
		blocks.setColspan(2);
		footer.addCell(blocks);
		PdfPCell legend = centered(
				"Este documento es la representación impresa de un comprobante electrónico autorizado por el SRI.",
				SMALL);
		legend.setColspan(2);
		legend.setPaddingTop(8);
		footer.addCell(legend);
		return footer;
	}

	/** Celda sin borde que envuelve una tabla; la última fila no se estira hasta el alto de la celda. */
	public static PdfPCell wrap(PdfPTable table) {
		PdfPCell cell = new PdfPCell(table);
		cell.setBorder(Rectangle.NO_BORDER);
		table.setExtendLastRow(false);
		return cell;
	}

	/** Celda vacía sin borde para separar bloques apilados. */
	public static PdfPCell gap() {
		PdfPCell gap = new PdfPCell(new Phrase(" ", SMALL));
		gap.setBorder(Rectangle.NO_BORDER);
		return gap;
	}

	/** Los códigos 6 y 7 del catálogo de IVA no son tarifas: no objeto y exento (ficha, Tabla 17). */
	public static String rateLabel(String rateCode, BigDecimal rate) {
		return switch (rateCode) {
			case "6" -> "No Objeto IVA";
			case "7" -> "Exento IVA";
			default -> "IVA " + percent(rate);
		};
	}

	public static String percent(BigDecimal rate) {
		return rate.stripTrailingZeros().setScale(Math.max(0, rate.stripTrailingZeros().scale()), RoundingMode.UNNECESSARY)
			.toPlainString() + "%";
	}

	/** Importe con al menos los decimales indicados; conserva más si el valor los tiene (precios con seis). */
	public static String amount(BigDecimal value, int minScale) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.setScale(Math.max(minScale, stripped.scale()), RoundingMode.UNNECESSARY).toPlainString();
	}

	/** Tipo de identificación del comprador (ficha, Tabla 6) como etiqueta; los demás, genérica. */
	private static String idLabel(String idType) {
		if (idType == null) {
			return "IDENTIFICACIÓN";
		}
		return switch (idType) {
			case "04" -> "RUC";
			case "05" -> "CÉDULA";
			case "06" -> "PASAPORTE";
			default -> "IDENTIFICACIÓN";
		};
	}

	private static String rimpeLegend(String regime) {
		if (regime == null) {
			return null;
		}
		return switch (regime) {
			case "EMPRENDEDOR" -> "CONTRIBUYENTE RÉGIMEN RIMPE";
			case "NEGOCIO_POPULAR" -> "CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE";
			default -> null;
		};
	}

	private static void totalRow(PdfPTable totals, String label, BigDecimal value, Font font) {
		PdfPCell name = new PdfPCell(new Phrase(clean(label), ON_NAVY));
		name.setBackgroundColor(NAVY);
		name.setHorizontalAlignment(Element.ALIGN_RIGHT);
		name.setBorder(Rectangle.BOTTOM);
		name.setBorderColor(Color.WHITE);
		name.setPadding(3);
		totals.addCell(name);
		PdfPCell amount = new PdfPCell(new Phrase(amount(value, 2), font));
		amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
		amount.setBorder(Rectangle.NO_BORDER);
		amount.setPadding(3);
		totals.addCell(amount);
	}

	private static PdfPCell barcode(String accessKey, PdfWriter writer) {
		Barcode128 code = new Barcode128();
		code.setCode(accessKey);
		code.setFont(null);
		code.setBarHeight(30);
		Image image = code.createImageWithBarcode(writer.getDirectContent(), null, null);
		PdfPCell cell = new PdfPCell(image, true);
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPaddingTop(2);
		cell.setPaddingBottom(1);
		return cell;
	}

	public static Paragraph spacer() {
		Paragraph spacer = new Paragraph(" ", SMALL);
		spacer.setLeading(8);
		return spacer;
	}

	/** Tabla de una columna sin bordes, para apilar líneas. */
	public static PdfPTable box() {
		PdfPTable table = new PdfPTable(1);
		table.setWidthPercentage(100);
		return table;
	}

	public static PdfPCell line(String value, Font font) {
		PdfPCell cell = new PdfPCell(new Phrase(clean(value), font));
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPadding(1.5f);
		return cell;
	}

	/** Una cifra larga (clave de acceso, autorización) entera y centrada en su línea: nunca se parte. */
	public static PdfPCell digits(String value) {
		PdfPCell cell = centered(value, SMALL);
		cell.setNoWrap(true);
		return cell;
	}

	/** Fila de fondo claro: etiqueta en negrita seguida del valor. */
	public static PdfPCell labelled(String label, String value) {
		Phrase phrase = new Phrase();
		phrase.add(new Phrase(label, BOLD));
		phrase.add(new Phrase(clean(value), NORMAL));
		return pale(new PdfPCell(phrase));
	}

	/** Celda del detalle, con una raya fina debajo. */
	public static PdfPCell text(String value, int alignment) {
		PdfPCell cell = new PdfPCell(new Phrase(clean(value), NORMAL));
		cell.setHorizontalAlignment(alignment);
		cell.setBorder(Rectangle.BOTTOM);
		cell.setBorderColor(RULE);
		cell.setPadding(3);
		return cell;
	}

	private static PdfPCell centered(String value, Font font) {
		PdfPCell cell = line(value, font);
		cell.setHorizontalAlignment(Element.ALIGN_CENTER);
		return cell;
	}

	/** Franja de título: texto centrado sobre un fondo de color. */
	private static PdfPCell band(String value, Font font, Color fill) {
		PdfPCell cell = new PdfPCell(new Phrase(clean(value), font));
		cell.setBackgroundColor(fill);
		cell.setHorizontalAlignment(Element.ALIGN_CENTER);
		cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
		cell.setBorder(Rectangle.BOTTOM);
		cell.setBorderColor(Color.WHITE);
		cell.setPadding(3);
		cell.setPaddingBottom(4);
		return cell;
	}

	/** Fondo claro con una línea blanca debajo que separa la fila de la siguiente. */
	private static PdfPCell pale(PdfPCell cell) {
		cell.setBackgroundColor(PALE);
		cell.setBorder(Rectangle.BOTTOM);
		cell.setBorderColor(Color.WHITE);
		cell.setBorderWidth(1);
		cell.setPadding(2.5f);
		return cell;
	}

	/**
	 * Deja el texto en lo que el PDF puede mostrar: los caracteres de control pasan a espacio y los
	 * que la fuente no tiene se omiten. No se añade ni se quita nada más: una palabra que no cabe en
	 * su celda la reparte la propia biblioteca entre varias líneas.
	 */
	public static String clean(String value) {
		if (value == null) {
			return "";
		}
		CharsetEncoder encoder = CP1252.newEncoder();
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length();) {
			int codePoint = value.codePointAt(i);
			i += Character.charCount(codePoint);
			if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
				out.append(' ');
			}
			else if (Character.isBmpCodePoint(codePoint) && encoder.canEncode((char) codePoint)) {
				out.append((char) codePoint);
			}
		}
		return out.toString().trim();
	}

}
