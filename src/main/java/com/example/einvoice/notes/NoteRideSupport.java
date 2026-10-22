package com.example.einvoice.notes;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.RideEnvelope;
import com.example.einvoice.delivery.RideLayout;

/**
 * Armazón común del RIDE de las notas: cabecera, receptor y cuadro de la factura que se modifica; el
 * detalle y el pie los aporta cada nota.
 */
final class NoteRideSupport {

	private NoteRideSupport() {
	}

	/** title va en mayúsculas en el RIDE; reason es nulo en la nota de débito. */
	static byte[] build(RideEnvelope ride, String title, String reason, PdfPTable detail, PdfPTable footer) {
		DocumentEnvelope envelope = ride.envelope();
		if (ride.authorizationNumber() == null || ride.authorizationNumber().isBlank()
				|| ride.authorizedAt() == null) {
			throw new IllegalArgumentException("Solo un comprobante autorizado tiene RIDE");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Document document = RideLayout.newDocument();
		try {
			PdfWriter writer = PdfWriter.getInstance(document, out);
			document.addTitle(title + " " + envelope.number());
			document.open();
			document.add(RideLayout.header(envelope.issuer(), title, envelope.number(), envelope.environment(),
					envelope.accessKey(), ride.authorizationNumber(), ride.authorizedAt(), writer));
			document.add(RideLayout.spacer());
			document.add(RideLayout.recipient(envelope.recipient(), envelope.issueDate()));
			document.add(RideLayout.spacer());
			document.add(modifiedDocument(envelope.modified(), reason));
			document.add(RideLayout.spacer());
			document.add(detail);
			document.add(RideLayout.spacer());
			document.add(footer);
			document.close();
		}
		catch (DocumentException ex) {
			throw new IllegalStateException("No se pudo generar el RIDE", ex);
		}
		return out.toByteArray();
	}

	private static PdfPTable modifiedDocument(DocumentEnvelope.ModifiedDocument modified, String reason) {
		Map<String, String> rows = new LinkedHashMap<>();
		rows.put("Comprobante:", "FACTURA " + modified.number());
		rows.put("Fecha de emisión:", RideLayout.DATE.format(modified.issueDate()));
		if (reason != null) {
			rows.put("Razón de modificación:", reason);
		}
		return RideLayout.infoTable("COMPROBANTE MODIFICADO", rows);
	}

	/** Bloques bajo los totales: la información adicional del receptor y, debajo, below (puede ser nulo). */
	static PdfPTable left(DocumentEnvelope envelope, PdfPTable below) {
		PdfPTable left = RideLayout.box();
		left.addCell(RideLayout.wrap(RideLayout.additionalInfo(envelope.recipient().email(),
				envelope.recipient().address(), envelope.providerRuc())));
		if (below != null) {
			left.addCell(RideLayout.gap());
			left.addCell(RideLayout.wrap(below));
		}
		return left;
	}

}
