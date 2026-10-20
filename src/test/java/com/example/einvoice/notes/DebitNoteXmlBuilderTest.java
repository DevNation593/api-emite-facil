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

/** Formato de nota de débito versión 1.0.0 (XSD NotaDebito_V1.0.0). */
class DebitNoteXmlBuilderTest {

	static final LocalDate ISSUE = LocalDate.of(2026, 10, 19);

	static final String ACCESS_KEY = new AccessKeyParts(ISSUE, "05", "1790012345001", "1", "001", "002", 7,
			"12345678", "1").toAccessKey();

	final XsdValidator validator = new XsdValidator(new DocumentSchemas());

	static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	static DocumentEnvelope envelope(String providerRuc) {
		return new DocumentEnvelope(DocumentType.DEBIT_NOTE, "1",
				new DocumentEnvelope.Issuer("Comercial Andina S.A.", "Andina", "1790012345001",
						"Av. Amazonas N34-12, Quito", "Av. 10 de Agosto, Quito", false, null, null, "NONE"),
				ACCESS_KEY, "001", "002", 7, ISSUE,
				new DocumentEnvelope.Recipient("05", "1712345678", "María Pérez", "Av. República, Quito", null),
				new DocumentEnvelope.Totals(d("15.00"), d("0.00"), d("2.25"), d("17.25")),
				new DocumentEnvelope.ModifiedDocument(null, "01", "001-001-000000123", LocalDate.of(2026, 10, 1)),
				providerRuc);
	}

	static DebitNoteContent content(String reason) {
		return new DebitNoteContent(
				List.of(new DebitNoteContent.Reason(reason, d("10.00"), "2", "4", d("15.00"), d("1.50")),
						new DebitNoteContent.Reason("Gastos de cobranza", d("5.00"), "2", "4", d("15.00"),
								d("0.75"))),
				List.of(new NoteTax("2", "4", d("15.00"), d("15.00"), d("2.25"))),
				List.of(new DebitNoteContent.Payment("01", d("17.25"))));
	}

	static String xml(DocumentEnvelope envelope, DebitNoteContent content) {
		return new String(DebitNoteXmlBuilder.build(envelope, content), StandardCharsets.UTF_8);
	}

	static final String EXPECTED = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
			+ "<notaDebito id=\"comprobante\" version=\"1.0.0\">"
			+ "<infoTributaria><ambiente>1</ambiente><tipoEmision>1</tipoEmision>"
			+ "<razonSocial>Comercial Andina S.A.</razonSocial><nombreComercial>Andina</nombreComercial>"
			+ "<ruc>1790012345001</ruc><claveAcceso>" + ACCESS_KEY + "</claveAcceso>"
			+ "<codDoc>05</codDoc><estab>001</estab><ptoEmi>002</ptoEmi><secuencial>000000007</secuencial>"
			+ "<dirMatriz>Av. Amazonas N34-12, Quito</dirMatriz></infoTributaria>"
			+ "<infoNotaDebito><fechaEmision>19/10/2026</fechaEmision>"
			+ "<dirEstablecimiento>Av. 10 de Agosto, Quito</dirEstablecimiento>"
			+ "<tipoIdentificacionComprador>05</tipoIdentificacionComprador>"
			+ "<razonSocialComprador>María Pérez</razonSocialComprador>"
			+ "<identificacionComprador>1712345678</identificacionComprador>"
			+ "<obligadoContabilidad>NO</obligadoContabilidad>"
			+ "<codDocModificado>01</codDocModificado><numDocModificado>001-001-000000123</numDocModificado>"
			+ "<fechaEmisionDocSustento>01/10/2026</fechaEmisionDocSustento>"
			+ "<totalSinImpuestos>15.00</totalSinImpuestos><impuestos>"
			+ "<impuesto><codigo>2</codigo><codigoPorcentaje>4</codigoPorcentaje><tarifa>15.00</tarifa>"
			+ "<baseImponible>15.00</baseImponible><valor>2.25</valor></impuesto></impuestos>"
			+ "<valorTotal>17.25</valorTotal>"
			+ "<pagos><pago><formaPago>01</formaPago><total>17.25</total></pago></pagos></infoNotaDebito>"
			+ "<motivos><motivo><razon>Intereses de mora</razon><valor>10.00</valor></motivo>"
			+ "<motivo><razon>Gastos de cobranza</razon><valor>5.00</valor></motivo></motivos></notaDebito>";

	@Test
	void producesExpectedXml() {
		assertThat(xml(envelope(null), content("Intereses de mora"))).isEqualTo(EXPECTED);
	}

	@Test
	void isValidAgainstXsdAndEnvelopeRules() {
		byte[] bytes = DebitNoteXmlBuilder.build(envelope("1790099999001"), content("Cambio & <ajuste>"));
		ValidationResult result = validator.validate(bytes, DocumentSchema.DEBIT_NOTE_1_0_0);
		assertThat(result.errors()).isEmpty();
		assertThat(EnvelopeRules.check(bytes, DocumentSchema.DEBIT_NOTE_1_0_0)).isEmpty();
		assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("<razon>Cambio &amp; &lt;ajuste&gt;</razon>")
			.endsWith("</motivos><infoAdicional><campoAdicional nombre=\"RUC Proveedor\">1790099999001"
					+ "</campoAdicional></infoAdicional></notaDebito>");
	}

	@Test
	void sameInputGivesSameBytes() {
		assertThat(DebitNoteXmlBuilder.build(envelope(null), content("Intereses de mora")))
			.isEqualTo(DebitNoteXmlBuilder.build(envelope(null), content("Intereses de mora")));
	}

}
