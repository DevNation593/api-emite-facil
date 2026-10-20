package com.example.einvoice.delivery;

import static com.example.einvoice.delivery.RideLayout.BOLD;
import static com.example.einvoice.delivery.RideLayout.SMALL;
import static com.example.einvoice.delivery.RideLayout.amount;
import static com.example.einvoice.delivery.RideLayout.box;
import static com.example.einvoice.delivery.RideLayout.framed;
import static com.example.einvoice.delivery.RideLayout.labelled;
import static com.example.einvoice.delivery.RideLayout.line;
import static com.example.einvoice.delivery.RideLayout.spacer;
import static com.example.einvoice.delivery.RideLayout.text;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.notes.NoteTax;
import com.example.einvoice.xml.InvoiceXmlData;

/**
 * Representación impresa (RIDE) de una factura autorizada, en PDF, con la disposición habitual de
 * estos documentos: emisor a la izquierda; RUC, número, autorización y clave de acceso a la derecha;
 * adquirente; detalle; información adicional, formas de pago y totales. Está pendiente de cotejar
 * campo a campo con el modelo del Anexo 2 de la ficha técnica v2.34, que es una imagen.
 *
 * <p>El RIDE no sustituye al XML firmado: lo representa. Las piezas comunes con las notas están en
 * {@link RideLayout}.
 */
public final class RidePdfBuilder {

	private RidePdfBuilder() {
	}

	public static byte[] build(RideData data) {
		if (data.authorizationNumber() == null || data.authorizationNumber().isBlank()
				|| data.authorizedAt() == null) {
			throw new IllegalArgumentException("Solo un comprobante autorizado tiene RIDE");
		}
		InvoiceXmlData invoice = data.invoice();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Document document = RideLayout.newDocument();
		try {
			PdfWriter writer = PdfWriter.getInstance(document, out);
			document.addTitle("Factura " + number(invoice));
			document.open();
			document.add(RideLayout.header(
					new DocumentEnvelope.Issuer(invoice.issuerLegalName(), invoice.issuerTradeName(),
							invoice.issuerRuc(), invoice.headOfficeAddress(), invoice.establishmentAddress(),
							invoice.accountingRequired(), invoice.specialTaxpayerCode(),
							invoice.withholdingAgentCode(), invoice.rimpeRegime()),
					"FACTURA", number(invoice), invoice.environment(), invoice.accessKey(),
					data.authorizationNumber(), data.authorizedAt(), writer));
			document.add(spacer());
			document.add(RideLayout.recipient(new DocumentEnvelope.Recipient(invoice.customerIdType(),
					invoice.customerIdNumber(), invoice.customerName(), invoice.customerAddress(), null),
					invoice.issueDate()));
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

	private static PdfPTable detail(InvoiceXmlData invoice) {
		PdfPTable table = RideLayout.detailTable(new float[] { 14, 9, 41, 13, 10, 13 }, "Cod. Principal", "Cantidad",
				"Descripción", "Precio Unitario", "Descuento", "Precio Total");
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
		payments.addCell(RideLayout.heading("Forma de pago"));
		payments.addCell(RideLayout.heading("Valor"));
		for (InvoiceXmlData.Payment payment : invoice.payments()) {
			String name = data.paymentMethodNames().get(payment.methodCode());
			payments.addCell(text(name == null ? payment.methodCode() : payment.methodCode() + " - " + name,
					Element.ALIGN_LEFT));
			payments.addCell(text(amount(payment.amount(), 2), Element.ALIGN_RIGHT));
		}
		left.addCell(RideLayout.wrap(payments));

		List<NoteTax> taxes = invoice.taxes()
			.stream()
			.map(tax -> new NoteTax(tax.taxCode(), tax.rateCode(), tax.rate(), tax.base(), tax.amount()))
			.toList();
		PdfPTable totals = RideLayout.totalsTable();
		RideLayout.subtotalRows(totals, taxes);
		RideLayout.total(totals, "SUBTOTAL SIN IMPUESTOS", invoice.subtotal());
		RideLayout.total(totals, "TOTAL Descuento", invoice.totalDiscount());
		RideLayout.vatRows(totals, taxes);
		RideLayout.grandTotal(totals, "VALOR TOTAL", invoice.total());
		return RideLayout.footer(left, totals);
	}

	private static String number(InvoiceXmlData invoice) {
		return invoice.establishmentCode() + "-" + invoice.emissionPointCode() + "-"
				+ String.format(Locale.ROOT, "%09d", invoice.sequence());
	}

}
