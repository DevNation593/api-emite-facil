package com.example.einvoice.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import com.example.einvoice.billing.RideEnvelope;

/** El RIDE de cada nota muestra la factura que modifica, su motivo o razones y sus totales. */
class NoteRideTest {

	private static final OffsetDateTime AUTHORIZED_AT = OffsetDateTime.parse("2026-10-19T20:15:30Z");

	private static String text(byte[] pdf) throws IOException {
		PdfReader reader = new PdfReader(pdf);
		try {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			StringBuilder all = new StringBuilder();
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				all.append(extractor.getTextFromPage(page)).append(' ');
			}
			return all.toString().replaceAll("\\s+", " ");
		}
		finally {
			reader.close();
		}
	}

	@Test
	void creditNoteShowsModifiedDocumentAndReason() throws IOException {
		RideEnvelope ride = new RideEnvelope(CreditNoteXmlBuilderTest.envelope(),
				CreditNoteXmlBuilderTest.ACCESS_KEY, AUTHORIZED_AT);
		byte[] pdf = CreditNoteRide.build(ride, CreditNoteXmlBuilderTest.content());
		// Queda en target/ para poder abrirlo y revisar el aspecto del RIDE de las notas.
		Files.write(Path.of("target", "ride-nota-credito.pdf"), pdf);
		String text = text(pdf);

		assertThat(text).contains("NOTA DE CRÉDITO", "001-002-000000007", "COMPROBANTE MODIFICADO", "FACTURA",
				"001-001-000000123", "01/10/2026", "Razón de modificación", "Devolución de mercadería",
				"Servicio de consultoría", "Subtotal IVA 15%", "VALOR MODIFICACIÓN", "37.00",
				CreditNoteXmlBuilderTest.ACCESS_KEY);
	}

	@Test
	void debitNoteShowsReasonsAndPayment() throws IOException {
		RideEnvelope ride = new RideEnvelope(DebitNoteXmlBuilderTest.envelope(null),
				DebitNoteXmlBuilderTest.ACCESS_KEY, AUTHORIZED_AT);
		String text = text(DebitNoteRide.build(ride, DebitNoteXmlBuilderTest.content("Intereses de mora"),
				Map.of("01", "SIN UTILIZACION DEL SISTEMA FINANCIERO")));

		assertThat(text).contains("NOTA DE DÉBITO", "COMPROBANTE MODIFICADO", "FACTURA", "001-001-000000123",
				"01/10/2026", "Intereses de mora", "10.00", "Gastos de cobranza", "5.00", "TOTAL", "17.25",
				"FORMA DE PAGO", "SIN UTILIZACION DEL SISTEMA FINANCIERO", DebitNoteXmlBuilderTest.ACCESS_KEY);
		assertThat(text).doesNotContain("Razón de modificación");
	}

	@Test
	void unauthorizedNoteHasNoRide() {
		RideEnvelope ride = new RideEnvelope(CreditNoteXmlBuilderTest.envelope(),
				CreditNoteXmlBuilderTest.ACCESS_KEY, null);
		assertThatThrownBy(() -> CreditNoteRide.build(ride, CreditNoteXmlBuilderTest.content()))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
