package com.example.einvoice.delivery;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
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

import com.example.einvoice.xml.InvoiceXmlData;

/**
 * Representación impresa (RIDE) de una factura autorizada, en PDF, con la disposición habitual de
 * estos documentos: emisor a la izquierda; RUC, número, autorización y clave de acceso a la derecha;
 * adquirente; detalle; información adicional, formas de pago y totales. Está pendiente de cotejar
 * campo a campo con el modelo del Anexo 2 de la ficha técnica v2.34, que es una imagen.
 *
 * <p>El RIDE no sustituye al XML firmado: lo representa. Usa Helvetica, así que los caracteres que
 * esa fuente no tiene (fuera de CP1252) se omiten.
 */
public final class RidePdfBuilder {

	/** Hora de Ecuador continental. */
	private static final ZoneOffset ECUADOR = ZoneOffset.ofHours(-5);

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

	private static final Charset CP1252 = Charset.forName("windows-1252");

	private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);

	private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

	private static final Font NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 8);

	private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7);

	private static final Color HEADER_FILL = new Color(230, 230, 230);

	private RidePdfBuilder() {
	}

	public static byte[] build(RideData data) {
		if (data.authorizationNumber() == null || data.authorizationNumber().isBlank()
				|| data.authorizedAt() == null) {
			throw new IllegalArgumentException("Solo un comprobante autorizado tiene RIDE");
		}
		InvoiceXmlData invoice = data.invoice();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Document document = new Document(PageSize.A4, 28, 28, 28, 28);
		try {
			PdfWriter writer = PdfWriter.getInstance(document, out);
			document.addTitle("Factura " + number(invoice));
			document.open();
			document.add(header(data, writer));
			document.add(spacer());
			document.add(buyer(invoice));
			document.add(spacer());
			document.add(detail(invoice));
			document.add(spacer());
			document.add(footer(data));
			document.close();
		}
		catch (DocumentException ex) {
			throw new IllegalStateException("No se pudo generar el RIDE", ex);
		}
		return out.toByteArray();
	}

	private static PdfPTable header(RideData data, PdfWriter writer) {
		InvoiceXmlData invoice = data.invoice();
		PdfPTable issuer = box();
		issuer.addCell(line(invoice.issuerLegalName(), TITLE));
		if (invoice.issuerTradeName() != null) {
			issuer.addCell(line(invoice.issuerTradeName(), BOLD));
		}
		issuer.addCell(labelled("Dirección Matriz: ", invoice.headOfficeAddress()));
		issuer.addCell(labelled("Dirección Sucursal: ", invoice.establishmentAddress()));
		if (invoice.specialTaxpayerCode() != null) {
			issuer.addCell(labelled("Contribuyente Especial Nro: ", invoice.specialTaxpayerCode()));
		}
		issuer.addCell(labelled("OBLIGADO A LLEVAR CONTABILIDAD: ", invoice.accountingRequired() ? "SI" : "NO"));
		if (invoice.withholdingAgentCode() != null) {
			issuer.addCell(labelled("Agente de Retención Resolución No. ", invoice.withholdingAgentCode()));
		}
		String rimpe = rimpeLegend(invoice.rimpeRegime());
		if (rimpe != null) {
			issuer.addCell(line(rimpe, BOLD));
		}

		PdfPTable fiscal = box();
		fiscal.addCell(labelled("R.U.C.: ", invoice.issuerRuc()));
		fiscal.addCell(line("FACTURA", TITLE));
		fiscal.addCell(labelled("No. ", number(invoice)));
		fiscal.addCell(line("NÚMERO DE AUTORIZACIÓN", BOLD));
		fiscal.addCell(digits(data.authorizationNumber()));
		fiscal.addCell(labelled("FECHA Y HORA DE AUTORIZACIÓN: ",
				DATE_TIME.format(data.authorizedAt().withOffsetSameInstant(ECUADOR))));
		fiscal.addCell(labelled("AMBIENTE: ", "2".equals(invoice.environment()) ? "PRODUCCIÓN" : "PRUEBAS"));
		fiscal.addCell(labelled("EMISIÓN: ", "NORMAL"));
		fiscal.addCell(line("CLAVE DE ACCESO", BOLD));
		fiscal.addCell(barcode(invoice.accessKey(), writer));
		fiscal.addCell(digits(invoice.accessKey()));

		PdfPTable header = new PdfPTable(new float[] { 48, 52 });
		header.setWidthPercentage(100);
		header.addCell(framed(issuer));
		header.addCell(framed(fiscal));
		return header;
	}

	private static PdfPTable buyer(InvoiceXmlData invoice) {
		PdfPTable inner = new PdfPTable(new float[] { 62, 38 });
		inner.setWidthPercentage(100);
		inner.addCell(plain(labelled("Razón Social / Nombres y Apellidos: ", invoice.customerName())));
		inner.addCell(plain(labelled("Identificación: ", invoice.customerIdNumber())));
		inner.addCell(plain(labelled("Dirección: ", invoice.customerAddress() == null ? "" : invoice.customerAddress())));
		inner.addCell(plain(labelled("Fecha Emisión: ", DATE.format(invoice.issueDate()))));
		PdfPTable buyer = new PdfPTable(1);
		buyer.setWidthPercentage(100);
		buyer.addCell(framed(inner));
		return buyer;
	}

	private static PdfPTable detail(InvoiceXmlData invoice) {
		PdfPTable table = new PdfPTable(new float[] { 14, 9, 41, 13, 10, 13 });
		table.setWidthPercentage(100);
		table.setHeaderRows(1);
		for (String title : new String[] { "Cod. Principal", "Cantidad", "Descripción", "Precio Unitario", "Descuento",
				"Precio Total" }) {
			PdfPCell cell = new PdfPCell(new Phrase(title, BOLD));
			cell.setBackgroundColor(HEADER_FILL);
			cell.setHorizontalAlignment(Element.ALIGN_CENTER);
			cell.setPadding(3);
			table.addCell(cell);
		}
		for (InvoiceXmlData.Line line : invoice.lines()) {
			table.addCell(text(line.mainCode(), Element.ALIGN_LEFT));
			table.addCell(text(amount(line.quantity(), 2), Element.ALIGN_RIGHT));
			table.addCell(text(line.description(), Element.ALIGN_LEFT));
			table.addCell(text(amount(line.unitPrice(), 2), Element.ALIGN_RIGHT));
			table.addCell(text(amount(line.discount(), 2), Element.ALIGN_RIGHT));
			table.addCell(text(amount(line.subtotal(), 2), Element.ALIGN_RIGHT));
		}
		return table;
	}

	private static PdfPTable footer(RideData data) {
		InvoiceXmlData invoice = data.invoice();
		PdfPTable left = new PdfPTable(1);
		left.setWidthPercentage(100);
		if (data.customerEmail() != null || invoice.providerRuc() != null) {
			PdfPTable additional = box();
			additional.addCell(line("Información Adicional", BOLD));
			if (data.customerEmail() != null) {
				additional.addCell(labelled("Email: ", data.customerEmail()));
			}
			if (invoice.providerRuc() != null) {
				additional.addCell(labelled("RUC Proveedor: ", invoice.providerRuc()));
			}
			left.addCell(framed(additional));
			PdfPCell gap = new PdfPCell(new Phrase(" ", SMALL));
			gap.setBorder(Rectangle.NO_BORDER);
			left.addCell(gap);
		}
		PdfPTable payments = new PdfPTable(new float[] { 75, 25 });
		payments.setWidthPercentage(100);
		payments.addCell(heading("Forma de pago"));
		payments.addCell(heading("Valor"));
		for (InvoiceXmlData.Payment payment : invoice.payments()) {
			String name = data.paymentMethodNames().get(payment.methodCode());
			payments.addCell(text(name == null ? payment.methodCode() : payment.methodCode() + " - " + name,
					Element.ALIGN_LEFT));
			payments.addCell(text(amount(payment.amount(), 2), Element.ALIGN_RIGHT));
		}
		PdfPCell paymentsCell = new PdfPCell(payments);
		paymentsCell.setBorder(Rectangle.NO_BORDER);
		left.addCell(paymentsCell);

		PdfPTable totals = new PdfPTable(new float[] { 65, 35 });
		totals.setWidthPercentage(100);
		for (InvoiceXmlData.Tax tax : invoice.taxes()) {
			total(totals, "SUBTOTAL " + rateLabel(tax), tax.base());
		}
		total(totals, "SUBTOTAL SIN IMPUESTOS", invoice.subtotal());
		total(totals, "TOTAL Descuento", invoice.totalDiscount());
		for (InvoiceXmlData.Tax tax : invoice.taxes()) {
			if (tax.rate().signum() > 0) {
				total(totals, "IVA " + percent(tax.rate()), tax.amount());
			}
		}
		totals.addCell(new PdfPCell(new Phrase("VALOR TOTAL", BOLD)));
		PdfPCell grand = new PdfPCell(new Phrase(amount(invoice.total(), 2), BOLD));
		grand.setHorizontalAlignment(Element.ALIGN_RIGHT);
		totals.addCell(grand);

		PdfPTable footer = new PdfPTable(new float[] { 58, 4, 38 });
		footer.setWidthPercentage(100);
		// Si el pie no cabe entero al final de una página, pasa completo a la siguiente.
		footer.setKeepTogether(true);
		PdfPCell leftCell = new PdfPCell(left);
		leftCell.setBorder(Rectangle.NO_BORDER);
		footer.addCell(leftCell);
		PdfPCell gap = new PdfPCell(new Phrase(""));
		gap.setBorder(Rectangle.NO_BORDER);
		footer.addCell(gap);
		PdfPCell totalsCell = new PdfPCell(totals);
		totalsCell.setBorder(Rectangle.NO_BORDER);
		footer.addCell(totalsCell);
		return footer;
	}

	private static void total(PdfPTable totals, String label, BigDecimal value) {
		totals.addCell(text(label, Element.ALIGN_LEFT));
		totals.addCell(text(amount(value, 2), Element.ALIGN_RIGHT));
	}

	/** Los códigos 6 y 7 del catálogo de IVA no son tarifas: no objeto y exento (ficha, Tabla 17). */
	private static String rateLabel(InvoiceXmlData.Tax tax) {
		return switch (tax.rateCode()) {
			case "6" -> "No objeto de IVA";
			case "7" -> "Exento de IVA";
			default -> percent(tax.rate());
		};
	}

	private static String percent(BigDecimal rate) {
		return rate.stripTrailingZeros().setScale(Math.max(0, rate.stripTrailingZeros().scale()), RoundingMode.UNNECESSARY)
			.toPlainString() + "%";
	}

	/** Importe con al menos los decimales indicados; conserva más si el valor los tiene (precios con seis). */
	private static String amount(BigDecimal value, int minScale) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.setScale(Math.max(minScale, stripped.scale()), RoundingMode.UNNECESSARY).toPlainString();
	}

	private static String number(InvoiceXmlData invoice) {
		return invoice.establishmentCode() + "-" + invoice.emissionPointCode() + "-"
				+ String.format(Locale.ROOT, "%09d", invoice.sequence());
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

	private static Paragraph spacer() {
		Paragraph spacer = new Paragraph(" ", SMALL);
		spacer.setLeading(6);
		return spacer;
	}

	/** Tabla de una columna sin bordes, para apilar líneas dentro de un recuadro. */
	private static PdfPTable box() {
		PdfPTable table = new PdfPTable(1);
		table.setWidthPercentage(100);
		return table;
	}

	private static PdfPCell framed(PdfPTable content) {
		PdfPCell cell = new PdfPCell(content);
		cell.setPadding(5);
		return cell;
	}

	private static PdfPCell plain(PdfPCell cell) {
		cell.setBorder(Rectangle.NO_BORDER);
		return cell;
	}

	private static PdfPCell line(String value, Font font) {
		PdfPCell cell = new PdfPCell(new Phrase(clean(value), font));
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPadding(1.5f);
		return cell;
	}

	/** Una cifra larga (clave de acceso, autorización) entera en su línea: nunca se parte. */
	private static PdfPCell digits(String value) {
		PdfPCell cell = new PdfPCell(new Phrase(value, SMALL));
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPadding(1.5f);
		cell.setNoWrap(true);
		return cell;
	}

	private static PdfPCell labelled(String label, String value) {
		Phrase phrase = new Phrase();
		phrase.add(new Phrase(label, BOLD));
		phrase.add(new Phrase(clean(value), NORMAL));
		PdfPCell cell = new PdfPCell(phrase);
		cell.setBorder(Rectangle.NO_BORDER);
		cell.setPadding(1.5f);
		return cell;
	}

	private static PdfPCell heading(String title) {
		PdfPCell cell = new PdfPCell(new Phrase(title, BOLD));
		cell.setBackgroundColor(HEADER_FILL);
		cell.setPadding(3);
		return cell;
	}

	private static PdfPCell text(String value, int alignment) {
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
	static String clean(String value) {
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
