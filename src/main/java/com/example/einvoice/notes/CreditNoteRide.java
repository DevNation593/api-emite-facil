package com.example.einvoice.notes;

import org.openpdf.text.Element;
import org.openpdf.text.pdf.PdfPTable;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.RideEnvelope;
import com.example.einvoice.delivery.RideLayout;

/** RIDE de una nota de crédito autorizada: la cabecera común, la factura que modifica, el detalle y los totales. */
public final class CreditNoteRide {

	private CreditNoteRide() {
	}

	public static byte[] build(RideEnvelope ride, CreditNoteContent content) {
		return NoteRideSupport.build(ride, "NOTA DE CRÉDITO", content.reason(), detail(content),
				footer(ride.envelope(), content));
	}

	private static PdfPTable detail(CreditNoteContent content) {
		PdfPTable table = RideLayout.detailTable(new float[] { 13, 10, 38, 13, 13, 13 }, "CÓDIGO", "CANTIDAD",
				"DESCRIPCIÓN", "V. UNITARIO", "DESCUENTO", "V. TOTAL");
		for (CreditNoteContent.Line line : content.lines()) {
			table.addCell(RideLayout.text(line.mainCode(), Element.ALIGN_LEFT));
			table.addCell(RideLayout.text(RideLayout.amount(line.quantity(), 2), Element.ALIGN_RIGHT));
			table.addCell(RideLayout.text(line.description(), Element.ALIGN_LEFT));
			table.addCell(RideLayout.text(RideLayout.amount(line.unitPrice(), 2), Element.ALIGN_RIGHT));
			table.addCell(RideLayout.text(RideLayout.amount(line.discount(), 2), Element.ALIGN_RIGHT));
			table.addCell(RideLayout.text(RideLayout.amount(line.subtotal(), 2), Element.ALIGN_RIGHT));
		}
		return table;
	}

	private static PdfPTable footer(DocumentEnvelope envelope, CreditNoteContent content) {
		DocumentEnvelope.Totals amounts = envelope.totals();
		PdfPTable totals = RideLayout.totalsTable();
		RideLayout.subtotalRows(totals, content.taxes());
		RideLayout.total(totals, "Subtotal sin impuestos", amounts.subtotal());
		if (amounts.totalDiscount() != null) {
			RideLayout.total(totals, "Descuento", amounts.totalDiscount());
		}
		RideLayout.vatRows(totals, content.taxes());
		RideLayout.grandTotal(totals, "VALOR MODIFICACIÓN", amounts.total());
		return RideLayout.footer(NoteRideSupport.left(envelope, null), totals);
	}

}
