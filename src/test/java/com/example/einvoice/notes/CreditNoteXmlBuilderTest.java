package com.example.einvoice.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.sequence.AccessKeyParts;
import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentSchemas;
import com.example.einvoice.xml.DocumentType;
import com.example.einvoice.xml.EnvelopeRules;
import com.example.einvoice.xml.ValidationResult;
import com.example.einvoice.xml.XsdValidator;

/** Formato de nota de crédito versión 1.1.0 (XSD NotaCredito_V1.1.0). */
class CreditNoteXmlBuilderTest {

	static final LocalDate ISSUE = LocalDate.of(2026, 10, 19);

	static final String ACCESS_KEY = new AccessKeyParts(ISSUE, "04", "1790012345001", "1", "001", "002", 7,
			"12345678", "1").toAccessKey();

	final XsdValidator validator = new XsdValidator(new DocumentSchemas());

	static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	static DocumentEnvelope envelope(String providerRuc, String rimpe, String withholding, String special,
			String tradeName, String address, boolean accounting) {
		return new DocumentEnvelope(DocumentType.CREDIT_NOTE, "1",
				new DocumentEnvelope.Issuer("Comercial Andina S.A.", tradeName, "1790012345001",
						"Av. Amazonas N34-12, Quito", address, accounting, special, withholding, rimpe, null),
				ACCESS_KEY, "001", "002", 7, ISSUE,
				new DocumentEnvelope.Recipient("05", "1712345678", "María Pérez", "Av. República, Quito", null, null),
				new DocumentEnvelope.Totals(d("34.00"), d("0.00"), d("3.00"), d("37.00")),
				new DocumentEnvelope.ModifiedDocument(null, "01", "001-001-000000123", LocalDate.of(2026, 10, 1)),
				providerRuc);
	}

	static DocumentEnvelope envelope() {
		return envelope(null, "NONE", null, null, "Andina", "Av. 10 de Agosto, Quito", false);
	}

	static CreditNoteContent content(String reason, String description) {
		return new CreditNoteContent(reason, List.of(
				new CreditNoteContent.Line(1, null, "SRV-001", description, d("2"), d("10.00"), d("0.00"),
						d("20.00"), "2", "4", d("15.00"), d("3.00")),
				new CreditNoteContent.Line(2, null, "LIB-001", "Libro", d("1.5"), d("9.333333"), d("0.00"),
						d("14.00"), "2", "0", d("0.00"), d("0.00"))),
				List.of(new NoteTax("2", "0", d("0.00"), d("14.00"), d("0.00")),
						new NoteTax("2", "4", d("15.00"), d("20.00"), d("3.00"))));
	}

	static CreditNoteContent content() {
		return content("Devolución de mercadería", "Servicio de consultoría");
	}

	static String xml(DocumentEnvelope envelope, CreditNoteContent content) {
		return new String(CreditNoteXmlBuilder.build(envelope, content), StandardCharsets.UTF_8);
	}

	static final String EXPECTED = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
			+ "<notaCredito id=\"comprobante\" version=\"1.1.0\">"
			+ "<infoTributaria><ambiente>1</ambiente><tipoEmision>1</tipoEmision>"
			+ "<razonSocial>Comercial Andina S.A.</razonSocial><nombreComercial>Andina</nombreComercial>"
			+ "<ruc>1790012345001</ruc><claveAcceso>" + ACCESS_KEY + "</claveAcceso>"
			+ "<codDoc>04</codDoc><estab>001</estab><ptoEmi>002</ptoEmi><secuencial>000000007</secuencial>"
			+ "<dirMatriz>Av. Amazonas N34-12, Quito</dirMatriz></infoTributaria>"
			+ "<infoNotaCredito><fechaEmision>19/10/2026</fechaEmision>"
			+ "<dirEstablecimiento>Av. 10 de Agosto, Quito</dirEstablecimiento>"
			+ "<tipoIdentificacionComprador>05</tipoIdentificacionComprador>"
			+ "<razonSocialComprador>María Pérez</razonSocialComprador>"
			+ "<identificacionComprador>1712345678</identificacionComprador>"
			+ "<obligadoContabilidad>NO</obligadoContabilidad>"
			+ "<codDocModificado>01</codDocModificado><numDocModificado>001-001-000000123</numDocModificado>"
			+ "<fechaEmisionDocSustento>01/10/2026</fechaEmisionDocSustento>"
			+ "<totalSinImpuestos>34.00</totalSinImpuestos><valorModificacion>37.00</valorModificacion>"
			+ "<moneda>DOLAR</moneda><totalConImpuestos>"
			+ "<totalImpuesto><codigo>2</codigo><codigoPorcentaje>0</codigoPorcentaje>"
			+ "<baseImponible>14.00</baseImponible><valor>0.00</valor></totalImpuesto>"
			+ "<totalImpuesto><codigo>2</codigo><codigoPorcentaje>4</codigoPorcentaje>"
			+ "<baseImponible>20.00</baseImponible><valor>3.00</valor></totalImpuesto>"
			+ "</totalConImpuestos><motivo>Devolución de mercadería</motivo></infoNotaCredito>"
			+ "<detalles>"
			+ "<detalle><codigoInterno>SRV-001</codigoInterno><descripcion>Servicio de consultoría</descripcion>"
			+ "<cantidad>2.000000</cantidad><precioUnitario>10.000000</precioUnitario><descuento>0.00</descuento>"
			+ "<precioTotalSinImpuesto>20.00</precioTotalSinImpuesto><impuestos><impuesto><codigo>2</codigo>"
			+ "<codigoPorcentaje>4</codigoPorcentaje><tarifa>15.00</tarifa><baseImponible>20.00</baseImponible>"
			+ "<valor>3.00</valor></impuesto></impuestos></detalle>"
			+ "<detalle><codigoInterno>LIB-001</codigoInterno><descripcion>Libro</descripcion>"
			+ "<cantidad>1.500000</cantidad><precioUnitario>9.333333</precioUnitario><descuento>0.00</descuento>"
			+ "<precioTotalSinImpuesto>14.00</precioTotalSinImpuesto><impuestos><impuesto><codigo>2</codigo>"
			+ "<codigoPorcentaje>0</codigoPorcentaje><tarifa>0.00</tarifa><baseImponible>14.00</baseImponible>"
			+ "<valor>0.00</valor></impuesto></impuestos></detalle>" + "</detalles></notaCredito>";

	void assertValid(DocumentEnvelope envelope, CreditNoteContent content) {
		byte[] bytes = CreditNoteXmlBuilder.build(envelope, content);
		ValidationResult result = validator.validate(bytes, DocumentSchema.CREDIT_NOTE_1_1_0);
		assertThat(result.errors()).isEmpty();
		assertThat(EnvelopeRules.check(bytes, DocumentSchema.CREDIT_NOTE_1_1_0)).isEmpty();
	}

	@Test
	void producesExpectedXml() {
		assertThat(xml(envelope(), content())).isEqualTo(EXPECTED);
	}

	@Test
	void isValidAgainstXsdAndEnvelopeRules() {
		assertValid(envelope(), content());
	}

	@Test
	void sameInputGivesSameBytes() {
		assertThat(CreditNoteXmlBuilder.build(envelope(), content()))
			.isEqualTo(CreditNoteXmlBuilder.build(envelope(), content()));
	}

	@Test
	void escapesSpecialCharacters() {
		DocumentEnvelope envelope = envelope();
		CreditNoteContent content = content("Cambio & <ajuste>", "Café & té <frío>");
		String xml = xml(envelope, content);
		assertThat(xml).contains("<motivo>Cambio &amp; &lt;ajuste&gt;</motivo>")
			.contains("<descripcion>Café &amp; té &lt;frío&gt;</descripcion>");
		assertValid(envelope, content);
	}

	@Test
	void includesLegendsSpecialTaxpayerAndProviderRuc() {
		DocumentEnvelope envelope = envelope("1790099999001", "EMPRENDEDOR", "1", "5368", null, null, true);
		String xml = xml(envelope, content());
		assertThat(xml).contains("<agenteRetencion>1</agenteRetencion>"
				+ "<contribuyenteRimpe>CONTRIBUYENTE RÉGIMEN RIMPE</contribuyenteRimpe></infoTributaria>")
			.contains("<identificacionComprador>1712345678</identificacionComprador>"
					+ "<contribuyenteEspecial>5368</contribuyenteEspecial>"
					+ "<obligadoContabilidad>SI</obligadoContabilidad><codDocModificado>")
			.doesNotContain("nombreComercial", "dirEstablecimiento")
			.endsWith("</detalles><infoAdicional><campoAdicional nombre=\"RUC Proveedor\">1790099999001"
					+ "</campoAdicional></infoAdicional></notaCredito>");
		assertValid(envelope, content());
	}

}
