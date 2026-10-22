package com.example.einvoice.delivery;

import static com.example.einvoice.delivery.RideLayout.amount;
import static com.example.einvoice.delivery.RideLayout.spacer;
import static com.example.einvoice.delivery.RideLayout.text;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.notes.NoteTax;
import com.example.einvoice.xml.InvoiceXmlData;

/**
 * Representación impresa (RIDE) de una factura autorizada, en PDF, con la disposición habitual de
 * estos documentos: emisor a la izquierda; RUC, número, autorización y clave de acceso a la derecha;
 * datos del cliente; detalle; totales a la derecha y, debajo, información adicional, formas de pago y
 * leyendas, cada bloque con su franja azul de título, como el modelo que se tomó de referencia.
 * Está pendiente de cotejar
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

	/** Columnas del modelo; el descuento de cada línea ya está restado en su valor total. */
	private static PdfPTable detail(InvoiceXmlData invoice) {
		PdfPTable table = RideLayout.detailTable(new float[] { 13, 10, 51, 13, 13 }, "CÓDIGO", "CANTIDAD",
				"DESCRIPCIÓN", "V. UNITARIO", "V. TOTAL");
		for (InvoiceXmlData.Line line : invoice.lines()) {
			table.addCell(text(line.mainCode(), Element.ALIGN_LEFT));
			table.addCell(text(amount(line.quantity(), 2), Element.ALIGN_RIGHT));
			table.addCell(text(line.description(), Element.ALIGN_LEFT));
			table.addCell(text(amount(line.unitPrice(), 2), Element.ALIGN_RIGHT));
			table.addCell(text(amount(line.subtotal(), 2), Element.ALIGN_RIGHT));
		}
		return table;
	}

	private static PdfPTable footer(RideData data) {
		InvoiceXmlData invoice = data.invoice();
		PdfPTable below = RideLayout.box();
		below.addCell(RideLayout.wrap(
				RideLayout.additionalInfo(data.customerEmail(), invoice.customerAddress(), invoice.providerRuc())));
		below.addCell(RideLayout.gap());
		PdfPTable payments = RideLayout.paymentsTable();
		for (InvoiceXmlData.Payment payment : invoice.payments()) {
			RideLayout.payment(payments, payment.methodCode(), data.paymentMethodNames().get(payment.methodCode()),
					payment.amount());
		}
		below.addCell(RideLayout.wrap(payments));

		List<NoteTax> taxes = invoice.taxes()
			.stream()
			.map(tax -> new NoteTax(tax.taxCode(), tax.rateCode(), tax.rate(), tax.base(), tax.amount()))
			.toList();
		PdfPTable totals = RideLayout.totalsTable();
		RideLayout.subtotalRows(totals, taxes);
		// La factura aún no admite propina.
		RideLayout.total(totals, "Propina", BigDecimal.ZERO);
		RideLayout.total(totals, "Descuento", invoice.totalDiscount());
		RideLayout.vatRows(totals, taxes);
		RideLayout.grandTotal(totals, "TOTAL", invoice.total());
		return RideLayout.footer(below, totals,
				"Favor remitir sus pagos y retenciones a nombre de \"" + invoice.issuerLegalName() + "\"");
	}

	private static String number(InvoiceXmlData invoice) {
		return invoice.establishmentCode() + "-" + invoice.emissionPointCode() + "-"
				+ String.format(Locale.ROOT, "%09d", invoice.sequence());
	}

}
