package com.example.einvoice.delivery;

import static com.example.einvoice.xml.InvoiceXmlFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import com.example.einvoice.xml.InvoiceXmlData;
import com.example.einvoice.xml.InvoiceXmlFixtures;

class RidePdfBuilderTest {

	private static final Map<String, String> PAYMENT_NAMES = Map.of("20",
			"OTROS CON UTILIZACIÓN DEL SISTEMA FINANCIERO", "01", "SIN UTILIZACION DEL SISTEMA FINANCIERO");

	private static final OffsetDateTime AUTHORIZED_AT = OffsetDateTime.parse("2026-10-07T20:15:30Z");

	private static RideData ride(InvoiceXmlData invoice) {
		return new RideData(invoice, InvoiceXmlFixtures.ACCESS_KEY, AUTHORIZED_AT, "cliente@example.test",
				PAYMENT_NAMES);
	}

	/** Texto de todas las páginas, con los espacios normalizados. */
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

	private static int pages(byte[] pdf) throws IOException {
		PdfReader reader = new PdfReader(pdf);
		try {
			return reader.getNumberOfPages();
		}
		finally {
			reader.close();
		}
	}

	@Test
	void containsEveryRequiredField() throws IOException {
		String text = text(RidePdfBuilder.build(ride(InvoiceXmlFixtures.standard())));

		assertThat(text).contains("R.U.C. 1790012345001", "FACTURA", "001-002-000000123", "Número Autorización SRI",
				"FECHA Y HORA DE AUTORIZACIÓN", "07/10/2026 15:15:30", "AMBIENTE:", "PRUEBAS", "ESTADO:", "AUTORIZADO",
				"EMISIÓN:", "NORMAL", "CLAVE DE ACCESO", InvoiceXmlFixtures.ACCESS_KEY);
		assertThat(text).contains("RAZÓN SOCIAL:", "Comercial Andina S.A.", "NOMBRE COMERCIAL:", "Andina",
				"DIRECCIÓN MATRIZ:", "Av. Amazonas N34-12, Quito", "DIRECCIÓN SUCURSAL:", "Av. 10 de Agosto, Quito",
				"OBLIGADO A LLEVAR CONTABILIDAD:", "NO", "TELÉFONO:");
		assertThat(text).contains("DATOS DEL CLIENTE", "María Pérez", "CÉDULA:", "1712345678", "FECHA DE EMISIÓN:",
				"07/10/2026");
		assertThat(text).contains("CÓDIGO", "CANTIDAD", "DESCRIPCIÓN", "V. UNITARIO", "V. TOTAL", "SRV-001",
				"Servicio de consultoría", "2.00", "10.00", "20.00", "LIB-001", "Libro", "1.50", "9.333333", "14.00");
		assertThat(text).contains("Subtotal IVA 15%", "Subtotal IVA 0%", "Subtotal IVA 5%", "Subtotal No Objeto IVA",
				"Subtotal Exento IVA", "Propina", "Descuento", "IVA 15%", "3.00", "IVA 5%", "TOTAL", "37.00");
		assertThat(text).contains("INFORMACIÓN ADICIONAL", "Correo Electrónico", "cliente@example.test", "Dirección",
				"Av. República, Quito", "Teléfono");
		assertThat(text).contains("FORMA DE PAGO", "PLAZO", "TIEMPO", "VALOR",
				"OTROS CON UTILIZACIÓN DEL SISTEMA FINANCIERO", "30.00", "SIN UTILIZACION DEL SISTEMA FINANCIERO",
				"7.00");
		assertThat(text).contains("Favor remitir sus pagos y retenciones a nombre de \"Comercial Andina S.A.\"",
				"validezComprobantes.jsf", "Cuidemos el medio ambiente.");
		// La estructura del modelo: sin columna de descuento, sin subtotal sin impuestos, la forma de pago por su nombre.
		assertThat(text).doesNotContain("DESCUENTO", "Subtotal sin impuestos", "20 - OTROS");
	}

	@Test
	void sameAddressIsPrintedOnce() throws IOException {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.establishmentAddress = "Av. Amazonas N34-12, Quito";

		String text = text(RidePdfBuilder.build(ride(builder.build())));

		assertThat(text).contains("DIRECCIÓN: Av. Amazonas N34-12, Quito").doesNotContain("DIRECCIÓN MATRIZ");
	}

	@Test
	void isAValidPdfWithABarcode() throws IOException {
		byte[] pdf = RidePdfBuilder.build(ride(InvoiceXmlFixtures.standard()));

		assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
		assertThat(pages(pdf)).isEqualTo(1);
		// El código de barras es el único objeto gráfico incrustado en la página.
		PdfReader reader = new PdfReader(pdf);
		try {
			PdfDictionary objects = reader.getPageN(1).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.XOBJECT);
			assertThat(objects).isNotNull();
			assertThat(objects.size()).isEqualTo(1);
		}
		finally {
			reader.close();
		}
	}

	@Test
	void optionalLegendsAppearOnlyWhenPresent() throws IOException {
		String plain = text(RidePdfBuilder.build(ride(InvoiceXmlFixtures.standard())));
		assertThat(plain).doesNotContain("CONTRIBUYENTE ESPECIAL", "AGENTE DE RETENCIÓN", "RIMPE", "RUC Proveedor");

		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.specialTaxpayerCode = "5368";
		builder.withholdingAgentCode = "1";
		builder.rimpeRegime = "EMPRENDEDOR";
		builder.accountingRequired = true;
		builder.providerRuc = "1790099999001";
		byte[] pdf = RidePdfBuilder.build(ride(builder.build()));
		// Queda en target/ para poder abrirlo y revisar el aspecto del RIDE.
		Files.write(Path.of("target", "ride-ejemplo.pdf"), pdf);
		String full = text(pdf);

		assertThat(full).contains("CONTRIBUYENTE ESPECIAL NRO: 5368", "AGENTE DE RETENCIÓN RESOLUCIÓN NO. 1",
				"CONTRIBUYENTE RÉGIMEN RIMPE", "RUC Proveedor", "1790099999001");
		assertThat(full).containsPattern("OBLIGADO A LLEVAR CONTABILIDAD: ?SI");
	}

	@Test
	void fixedTaxRowsAlwaysAppearAndOtherRatesAreAdded() throws IOException {
		InvoiceXmlData base = InvoiceXmlFixtures.standard();
		InvoiceXmlData invoice = new InvoiceXmlData(base.environment(), base.issuerLegalName(),
				base.issuerTradeName(), base.issuerRuc(), base.accessKey(), base.documentType(),
				base.establishmentCode(), base.emissionPointCode(), base.sequence(), base.headOfficeAddress(),
				base.withholdingAgentCode(), base.rimpeRegime(), base.issueDate(), base.establishmentAddress(),
				base.specialTaxpayerCode(), base.accountingRequired(), base.customerIdType(), base.customerName(),
				base.customerIdNumber(), base.customerAddress(), d("30.00"), d("1.25"),
				List.of(new InvoiceXmlData.Tax("2", "6", d("0.00"), d("10.00"), d("0.00")),
						new InvoiceXmlData.Tax("2", "7", d("0.00"), d("20.00"), d("0.00")),
						new InvoiceXmlData.Tax("2", "8", d("8.00"), d("5.00"), d("0.40"))),
				d("35.40"), base.lines(), base.payments(), base.providerRuc());

		String text = text(RidePdfBuilder.build(ride(invoice)));

		// Las filas del modelo salen aunque estén en cero; la tarifa del 8 % entra tras la del 5 %.
		assertThat(text).containsSubsequence("Subtotal IVA 15%", "0.00", "Subtotal IVA 0%", "0.00", "Subtotal IVA 5%",
				"0.00", "Subtotal IVA 8%", "5.00", "Subtotal No Objeto IVA", "10.00", "Subtotal Exento IVA", "20.00",
				"Propina", "0.00", "Descuento", "1.25", "IVA 15%", "0.00", "IVA 5%", "0.00", "IVA 8%", "0.40", "TOTAL",
				"35.40");
	}

	@Test
	void manyLinesFlowOntoMorePages() throws IOException {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		List<InvoiceXmlData.Line> lines = new ArrayList<>();
		for (int i = 1; i <= 300; i++) {
			lines.add(new InvoiceXmlData.Line("COD-" + i, "Producto número " + i, d("1"), d("1.00"), d("0.00"),
					d("1.00"), "2", "4", d("15.00"), d("0.15")));
		}
		builder.lines = lines;

		byte[] pdf = RidePdfBuilder.build(ride(builder.build()));

		assertThat(pages(pdf)).isGreaterThan(1);
		assertThat(text(pdf)).contains("COD-1 1.00 Producto número 1", "COD-300 1.00 Producto número 300",
				"Cuidemos el medio ambiente.");
	}

	@Test
	void oddCharactersNeverBreakTheRide() throws IOException {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.legalName = "Ñandú & Hijos <S.A.> \"Cía.\"";
		builder.tradeName = "Línea\r\nBcc: otra 😀 東京";
		builder.customerAddress = "x".repeat(300);
		builder.lines = List.of(new InvoiceXmlData.Line("A\u0000B", "Café\tcon leche — «especial» €5", d("1"),
				d("1.00"), d("0.00"), d("1.00"), "2", "4", d("15.00"), d("0.15")));

		String text = text(RidePdfBuilder.build(ride(builder.build())));

		assertThat(text).contains("Ñandú & Hijos <S.A.> \"Cía.\"", "Línea", "Bcc: otra", "Café", "con leche");
		// La palabra interminable se reparte en varias líneas, pero no pierde ni gana caracteres.
		assertThat(text.replaceAll("\\s", "")).contains("x".repeat(300)).doesNotContain("x".repeat(301));
	}

	@Test
	void longValuesArePrintedWhole() throws IOException {
		String longEmail = "contabilidad.cuentas.por.pagar@grupo-empresarial-andino.com.ec";
		String text = text(RidePdfBuilder.build(new RideData(InvoiceXmlFixtures.standard(),
				InvoiceXmlFixtures.ACCESS_KEY, AUTHORIZED_AT, longEmail, PAYMENT_NAMES)));

		// Un dato sin espacios no se parte con espacios inventados.
		assertThat(text).contains(longEmail);
	}

	@Test
	void unauthorizedDataIsRejected() {
		InvoiceXmlData invoice = InvoiceXmlFixtures.standard();

		assertThatThrownBy(
				() -> RidePdfBuilder.build(new RideData(invoice, null, AUTHORIZED_AT, null, PAYMENT_NAMES)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RidePdfBuilder
			.build(new RideData(invoice, InvoiceXmlFixtures.ACCESS_KEY, null, null, PAYMENT_NAMES)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void missingOptionalDataIsLeftOut() throws IOException {
		String text = text(RidePdfBuilder
			.build(new RideData(InvoiceXmlFixtures.standard(), InvoiceXmlFixtures.ACCESS_KEY, AUTHORIZED_AT, null, Map.of())));

		// Sin correo no hay fila de correo; una forma de pago sin nombre sale por su código.
		assertThat(text).doesNotContain("Correo Electrónico").contains("INFORMACIÓN ADICIONAL", "FORMA DE PAGO", "20",
				"30.00");
	}

}
