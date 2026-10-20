package com.example.einvoice.notes;

import java.util.Map;

import org.openpdf.text.Element;
import org.openpdf.text.pdf.PdfPTable;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.RideEnvelope;
import com.example.einvoice.delivery.RideLayout;

/**
 * RIDE de una nota de débito autorizada: la cabecera común, la factura que modifica, las razones con su valor,
 * la forma de pago y los totales.
 */
public final class DebitNoteRide {

	private DebitNoteRide() {
	}

	public static byte[] build(RideEnvelope ride, DebitNoteContent content, Map<String, String> paymentMethodNames) {
		return NoteRideSupport.build(ride, "NOTA DE DÉBITO", null, detail(content),
				footer(ride.envelope(), content, paymentMethodNames));
	}

	private static PdfPTable detail(DebitNoteContent content) {
		PdfPTable table = RideLayout.detailTable(new float[] { 80, 20 }, "Razón de la modificación", "Valor");
		for (DebitNoteContent.Reason reason : content.reasons()) {
			table.addCell(RideLayout.text(reason.reason(), Element.ALIGN_LEFT));
			table.addCell(RideLayout.text(RideLayout.amount(reason.value(), 2), Element.ALIGN_RIGHT));
		}
		return table;
	}

	private static PdfPTable footer(DocumentEnvelope envelope, DebitNoteContent content,
			Map<String, String> names) {
		PdfPTable payments = new PdfPTable(new float[] { 75, 25 });
		payments.setWidthPercentage(100);
		payments.addCell(RideLayout.heading("Forma de pago"));
		payments.addCell(RideLayout.heading("Valor"));
		for (DebitNoteContent.Payment payment : content.payments()) {
			String name = names.get(payment.methodCode());
			payments.addCell(RideLayout.text(
					name == null ? payment.methodCode() : payment.methodCode() + " - " + name, Element.ALIGN_LEFT));
			payments.addCell(RideLayout.text(RideLayout.amount(payment.amount(), 2), Element.ALIGN_RIGHT));
		}
		DocumentEnvelope.Totals amounts = envelope.totals();
		PdfPTable totals = RideLayout.totalsTable();
		RideLayout.subtotalRows(totals, content.taxes());
		RideLayout.total(totals, "SUBTOTAL SIN IMPUESTOS", amounts.subtotal());
		RideLayout.vatRows(totals, content.taxes());
		RideLayout.grandTotal(totals, "Valor total", amounts.total());
		return RideLayout.footer(NoteRideSupport.left(envelope, payments), totals);
	}

}
