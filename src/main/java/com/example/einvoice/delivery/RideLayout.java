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
 * Piezas comunes del RIDE: fuentes, cabecera con emisor, RUC, título, número, autorización y código de
 * barras de la clave de acceso, cuadro del receptor, filas de totales por tarifa, pie y celdas. La factura
 * y las notas las componen; el título y el número del documento son parámetros. Usa Helvetica, así que los
 * caracteres que esa fuente no tiene (fuera de CP1252) se omiten.
 */
public final class RideLayout {

	/** Hora de Ecuador continental. */
	private static final ZoneOffset ECUADOR = ZoneOffset.ofHours(-5);

	public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

	private static final Charset CP1252 = Charset.forName("windows-1252");

	public static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);

	public static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

	public static final Font NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 8);

	public static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7);

	public static final Color HEADER_FILL = new Color(230, 230, 230);

	private RideLayout() {
	}

	/** Documento A4 con los márgenes del RIDE. */
	public static Document newDocument() {
		return new Document(PageSize.A4, 28, 28, 28, 28);
	}

	/** Cabecera: emisor a la izquierda; RUC, título, número, autorización y clave de acceso a la derecha. */
	public static PdfPTable header(DocumentEnvelope.Issuer issuer, String title, String number, String environment,
			String accessKey, String authorizationNumber, OffsetDateTime authorizedAt, PdfWriter writer) {
		PdfPTable issuerBox = box();
		issuerBox.addCell(line(issuer.legalName(), TITLE));
		if (issuer.tradeName() != null) {
			issuerBox.addCell(line(issuer.tradeName(), BOLD));
		}
		issuerBox.addCell(labelled("Dirección Matriz: ", issuer.headOfficeAddress()));
		issuerBox.addCell(labelled("Dirección Sucursal: ", issuer.establishmentAddress()));
		if (issuer.specialTaxpayerCode() != null) {
			issuerBox.addCell(labelled("Contribuyente Especial Nro: ", issuer.specialTaxpayerCode()));
		}
		issuerBox.addCell(labelled("OBLIGADO A LLEVAR CONTABILIDAD: ", issuer.accountingRequired() ? "SI" : "NO"));
		if (issuer.withholdingAgentCode() != null) {
			issuerBox.addCell(labelled("Agente de Retención Resolución No. ", issuer.withholdingAgentCode()));
		}
		String rimpe = rimpeLegend(issuer.rimpeRegime());
		if (rimpe != null) {
			issuerBox.addCell(line(rimpe, BOLD));
		}

		PdfPTable fiscal = box();
		fiscal.addCell(labelled("R.U.C.: ", issuer.ruc()));
		fiscal.addCell(line(title, TITLE));
		fiscal.addCell(labelled("No. ", number));
		fiscal.addCell(line("NÚMERO DE AUTORIZACIÓN", BOLD));
		fiscal.addCell(digits(authorizationNumber));
		fiscal.addCell(labelled("FECHA Y HORA DE AUTORIZACIÓN: ",
				DATE_TIME.format(authorizedAt.withOffsetSameInstant(ECUADOR))));
		fiscal.addCell(labelled("AMBIENTE: ", "2".equals(environment) ? "PRODUCCIÓN" : "PRUEBAS"));
		fiscal.addCell(labelled("EMISIÓN: ", "NORMAL"));
		fiscal.addCell(line("CLAVE DE ACCESO", BOLD));
		fiscal.addCell(barcode(accessKey, writer));
		fiscal.addCell(digits(accessKey));

		PdfPTable header = new PdfPTable(new float[] { 48, 52 });
		header.setWidthPercentage(100);
		header.addCell(framed(issuerBox));
		header.addCell(framed(fiscal));
		return header;
	}

	/** Cuadro del receptor: nombre, identificación, dirección y fecha de emisión. */
	public static PdfPTable recipient(DocumentEnvelope.Recipient recipient, LocalDate issueDate) {
		PdfPTable inner = new PdfPTable(new float[] { 62, 38 });
		inner.setWidthPercentage(100);
		inner.addCell(plain(labelled("Razón Social / Nombres y Apellidos: ", recipient.name())));
		inner.addCell(plain(labelled("Identificación: ", recipient.idNumber())));
		inner.addCell(plain(labelled("Dirección: ", recipient.address() == null ? "" : recipient.address())));
		inner.addCell(plain(labelled("Fecha Emisión: ", DATE.format(issueDate))));
		PdfPTable buyer = new PdfPTable(1);
		buyer.setWidthPercentage(100);
		buyer.addCell(framed(inner));
		return buyer;
	}

	/** Detalle con encabezado gris; las filas las añade quien llama con {@link #text}. */
	public static PdfPTable detailTable(float[] widths, String... titles) {
		PdfPTable table = new PdfPTable(widths);
		table.setWidthPercentage(100);
		table.setHeaderRows(1);
		for (String title : titles) {
			PdfPCell cell = new PdfPCell(new Phrase(title, BOLD));
			cell.setBackgroundColor(HEADER_FILL);
			cell.setHorizontalAlignment(Element.ALIGN_CENTER);
			cell.setPadding(3);
			table.addCell(cell);
		}
		return table;
	}

	/** Tabla de totales de dos columnas, vacía. */
	public static PdfPTable totalsTable() {
		PdfPTable totals = new PdfPTable(new float[] { 65, 35 });
		totals.setWidthPercentage(100);
		return totals;
	}

	/** Filas «SUBTOTAL 15%», «SUBTOTAL No objeto de IVA»… una por tarifa. */
	public static void subtotalRows(PdfPTable totals, List<NoteTax> taxes) {
		for (NoteTax tax : taxes) {
			total(totals, "SUBTOTAL " + rateLabel(tax.rateCode(), tax.rate()), tax.base());
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
		totals.addCell(text(label, Element.ALIGN_LEFT));
		totals.addCell(text(amount(value, 2), Element.ALIGN_RIGHT));
	}

	/** Última fila de los totales, en negrita. */
	public static void grandTotal(PdfPTable totals, String label, BigDecimal value) {
		totals.addCell(new PdfPCell(new Phrase(label, BOLD)));
		PdfPCell grand = new PdfPCell(new Phrase(amount(value, 2), BOLD));
		grand.setHorizontalAlignment(Element.ALIGN_RIGHT);
		totals.addCell(grand);
	}

	/** Pie en dos columnas (58 % y 38 %) que, si no cabe al final de una página, pasa entero a la siguiente. */
	public static PdfPTable footer(PdfPTable left, PdfPTable right) {
		PdfPTable footer = new PdfPTable(new float[] { 58, 4, 38 });
		footer.setWidthPercentage(100);
		footer.setKeepTogether(true);
		PdfPCell leftCell = new PdfPCell(left);
		leftCell.setBorder(Rectangle.NO_BORDER);
		footer.addCell(leftCell);
		PdfPCell gap = new PdfPCell(new Phrase(""));
		gap.setBorder(Rectangle.NO_BORDER);
		footer.addCell(gap);
		PdfPCell rightCell = new PdfPCell(right);
		rightCell.setBorder(Rectangle.NO_BORDER);
		footer.addCell(rightCell);
		return footer;
	}

	/** Celda sin borde que envuelve una tabla. */
	public static PdfPCell wrap(PdfPTable table) {
		PdfPCell cell = new PdfPCell(table);
		cell.setBorder(Rectangle.NO_BORDER);
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
			case "6" -> "No objeto de IVA";
			case "7" -> "Exento de IVA";
			default -> percent(rate);
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

	private static PdfPCell barcode(String accessKey, PdfWriter writer) {
		Barcode128 code = new Barcode128();
		code.setCode(accessKey);
		code.setFont(null);
		code.setBarHeight(28);
		Image image = code.createImageWithBarcode(writer.getDirectContent(), null, null);
		PdfPCell cell = new PdfPCell(image, true);
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPaddingTop(2);
		cell.setPaddingBottom(2);
		return cell;
	}

	public static Paragraph spacer() {
		Paragraph spacer = new Paragraph(" ", SMALL);
		spacer.setLeading(6);
		return spacer;
	}

	/** Tabla de una columna sin bordes, para apilar líneas dentro de un recuadro. */
	public static PdfPTable box() {
		PdfPTable table = new PdfPTable(1);
		table.setWidthPercentage(100);
		return table;
	}

	public static PdfPCell framed(PdfPTable content) {
		PdfPCell cell = new PdfPCell(content);
		cell.setPadding(5);
		return cell;
	}

	public static PdfPCell plain(PdfPCell cell) {
		cell.setBorder(Rectangle.NO_BORDER);
		return cell;
	}

	public static PdfPCell line(String value, Font font) {
		PdfPCell cell = new PdfPCell(new Phrase(clean(value), font));
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPadding(1.5f);
		return cell;
	}

	/** Una cifra larga (clave de acceso, autorización) entera en su línea: nunca se parte. */
	public static PdfPCell digits(String value) {
		PdfPCell cell = new PdfPCell(new Phrase(value, SMALL));
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPadding(1.5f);
		cell.setNoWrap(true);
		return cell;
	}

	public static PdfPCell labelled(String label, String value) {
		Phrase phrase = new Phrase();
		phrase.add(new Phrase(label, BOLD));
		phrase.add(new Phrase(clean(value), NORMAL));
		PdfPCell cell = new PdfPCell(phrase);
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPadding(1.5f);
		return cell;
	}

	public static PdfPCell heading(String title) {
		PdfPCell cell = new PdfPCell(new Phrase(title, BOLD));
		cell.setBackgroundColor(HEADER_FILL);
		cell.setPadding(3);
		return cell;
	}

	public static PdfPCell text(String value, int alignment) {
		PdfPCell cell = new PdfPCell(new Phrase(clean(value), NORMAL));
		cell.setHorizontalAlignment(alignment);
		cell.setPadding(3);
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
