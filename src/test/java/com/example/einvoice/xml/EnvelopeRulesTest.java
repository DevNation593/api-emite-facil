package com.example.einvoice.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.example.einvoice.sequence.AccessKeyParts;

class EnvelopeRulesTest {

	/** Comprobante mínimo coherente con la clave de acceso del doc 06 (07/10/2026, 001-002, secuencial 123). */
	static final String VALID = "<factura id=\"comprobante\" version=\"2.1.0\"><infoTributaria>"
			+ "<ambiente>1</ambiente><tipoEmision>1</tipoEmision><ruc>1790012345001</ruc>" + "<claveAcceso>"
			+ InvoiceXmlFixtures.ACCESS_KEY + "</claveAcceso><codDoc>01</codDoc><estab>001</estab>"
			+ "<ptoEmi>002</ptoEmi><secuencial>000000123</secuencial></infoTributaria>"
			+ "<infoFactura><fechaEmision>07/10/2026</fechaEmision></infoFactura></factura>";

	static Set<EnvelopeViolation> check(String xml) {
		return EnvelopeRules.check(xml.getBytes(StandardCharsets.UTF_8), DocumentSchema.INVOICE_2_1_0);
	}

	@Test
	void officialSampleViolatesWhatTheXsdDoesNotCheck() throws IOException {
		byte[] sample;
		try (var in = getClass().getClassLoader().getResourceAsStream(DocumentSchema.INVOICE_2_1_0.sampleResource())) {
			sample = in.readAllBytes();
		}
		assertThat(EnvelopeRules.check(sample, DocumentSchema.INVOICE_2_1_0))
			.contains(EnvelopeViolation.COD_DOC_MISMATCH, EnvelopeViolation.VERSION_MISMATCH,
					EnvelopeViolation.SCHEMA_LOCATION_PRESENT)
			.containsAnyOf(EnvelopeViolation.ACCESS_KEY_INVALID, EnvelopeViolation.ACCESS_KEY_MISMATCH);
	}

	@Test
	void correctEnvelopePasses() {
		assertThat(check(VALID)).isEmpty();
	}

	@Test
	void missingIdIsReported() {
		assertThat(check(VALID.replace(" id=\"comprobante\"", ""))).containsExactly(EnvelopeViolation.ID_MISSING);
	}

	@Test
	void versionOfAnotherSchemaIsReported() {
		assertThat(check(VALID.replace("version=\"2.1.0\"", "version=\"1.1.0\"")))
			.containsExactly(EnvelopeViolation.VERSION_MISMATCH);
	}

	@Test
	void documentTypeOtherThanInvoiceIsReported() {
		assertThat(check(VALID.replace("<codDoc>01</codDoc>", "<codDoc>04</codDoc>")))
			.containsExactlyInAnyOrder(EnvelopeViolation.COD_DOC_MISMATCH, EnvelopeViolation.ACCESS_KEY_MISMATCH);
	}

	@Test
	void schemaLocationOnChildElementIsReported() {
		String xml = VALID.replace("<infoTributaria>",
				"<infoTributaria xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
						+ " xsi:noNamespaceSchemaLocation=\"http://127.0.0.1:9/evil.xsd\">");
		assertThat(check(xml)).containsExactly(EnvelopeViolation.SCHEMA_LOCATION_PRESENT);
	}

	@Test
	void detectsInvalidAccessKey() {
		String wrongCheckDigit = InvoiceXmlFixtures.ACCESS_KEY.substring(0, 48) + "7";
		assertThat(check(VALID.replace(InvoiceXmlFixtures.ACCESS_KEY, wrongCheckDigit)))
			.containsExactly(EnvelopeViolation.ACCESS_KEY_INVALID);
		assertThat(check(VALID.replace(InvoiceXmlFixtures.ACCESS_KEY, "123")))
			.containsExactly(EnvelopeViolation.ACCESS_KEY_INVALID);
		assertThat(check(VALID.replace("<claveAcceso>" + InvoiceXmlFixtures.ACCESS_KEY + "</claveAcceso>", "")))
			.containsExactly(EnvelopeViolation.ACCESS_KEY_INVALID);
	}

	@Test
	void detectsAccessKeyThatDoesNotMatchTheDocument() {
		String[][] changes = { { "<secuencial>000000123</secuencial>", "<secuencial>000000124</secuencial>" },
				{ "<ruc>1790012345001</ruc>", "<ruc>1790012345002</ruc>" },
				{ "<fechaEmision>07/10/2026</fechaEmision>", "<fechaEmision>08/10/2026</fechaEmision>" },
				{ "<fechaEmision>07/10/2026</fechaEmision>", "<fechaEmision>2026-10-07</fechaEmision>" },
				{ "<ambiente>1</ambiente>", "<ambiente>2</ambiente>" }, { "<estab>001</estab>", "<estab>002</estab>" },
				{ "<ptoEmi>002</ptoEmi>", "<ptoEmi>001</ptoEmi>" },
				{ "<tipoEmision>1</tipoEmision>", "<tipoEmision>2</tipoEmision>" },
				{ "<infoFactura><fechaEmision>07/10/2026</fechaEmision></infoFactura>", "" } };
		for (String[] change : changes) {
			assertThat(check(VALID.replace(change[0], change[1]))).as(change[1])
				.containsExactly(EnvelopeViolation.ACCESS_KEY_MISMATCH);
		}
	}

	@Test
	void elementsOutsideTheirPlaceAreNotTakenAsHeaderData() {
		// Un claveAcceso anidado en otro elemento no sustituye al de infoTributaria.
		String xml = VALID.replace("<claveAcceso>" + InvoiceXmlFixtures.ACCESS_KEY + "</claveAcceso>", "")
			.replace("</infoFactura>",
					"<otro><claveAcceso>" + InvoiceXmlFixtures.ACCESS_KEY + "</claveAcceso></otro></infoFactura>");
		assertThat(check(xml)).containsExactly(EnvelopeViolation.ACCESS_KEY_INVALID);
	}

	@Test
	void unreadableXmlThrows() {
		assertThatThrownBy(() -> check("<factura")).isInstanceOf(IllegalArgumentException.class);
	}

	static String sample(DocumentSchema schema) throws IOException {
		try (var in = EnvelopeRulesTest.class.getClassLoader().getResourceAsStream(schema.sampleResource())) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Ejemplo del esquema con código, versión, secuencial y clave coherentes y sin schemaLocation. */
	static String coherentSample(DocumentSchema schema) throws IOException {
		String xml = sample(schema).replaceAll("\s+xsi:noNamespaceSchemaLocation=\"[^\"]*\"", "")
			.replace("<codDoc>00</codDoc>", "<codDoc>" + schema.type().code() + "</codDoc>")
			.replace("version=\"version0\"", "version=\"" + schema.version() + "\"")
			.replace("<secuencial>000000000</secuencial>", "<secuencial>000000001</secuencial>");
		String key = new AccessKeyParts(LocalDate.of(2000, 1, 1), schema.type().code(), "0000000000001", "1", "000",
				"000", 1, "12345678", "1")
			.toAccessKey();
		return xml.replace("<claveAcceso>0000000000000000000000000000000000000000000000000</claveAcceso>",
				"<claveAcceso>" + key + "</claveAcceso>");
	}

	@ParameterizedTest
	@EnumSource(DocumentSchema.class)
	void acceptsEachTypeWithItsCodeAndVersion(DocumentSchema schema) throws IOException {
		assertThat(EnvelopeRules.check(coherentSample(schema).getBytes(StandardCharsets.UTF_8), schema)).isEmpty();
	}

	@Test
	void rejectsAnotherTypesCode() throws IOException {
		DocumentSchema schema = DocumentSchema.CREDIT_NOTE_1_1_0;
		String xml = coherentSample(schema).replace("<codDoc>04</codDoc>", "<codDoc>01</codDoc>");
		assertThat(EnvelopeRules.check(xml.getBytes(StandardCharsets.UTF_8), schema))
			.contains(EnvelopeViolation.COD_DOC_MISMATCH);
	}

	@Test
	void rejectsAnotherTypesRoot() throws IOException {
		byte[] debitNote = coherentSample(DocumentSchema.DEBIT_NOTE_1_0_0).getBytes(StandardCharsets.UTF_8);
		assertThat(EnvelopeRules.check(debitNote, DocumentSchema.CREDIT_NOTE_1_1_0))
			.contains(EnvelopeViolation.ROOT_MISMATCH);
	}

	@Test
	void remissionGuideKeyIsCheckedWithoutIssueDate() throws IOException {
		DocumentSchema schema = DocumentSchema.REMISSION_GUIDE_1_1_0;
		String xml = coherentSample(schema);
		assertThat(EnvelopeRules.check(xml.getBytes(StandardCharsets.UTF_8), schema)).isEmpty();
		String otherRuc = xml.replace("<ruc>0000000000001</ruc>", "<ruc>0000000000002</ruc>");
		assertThat(EnvelopeRules.check(otherRuc.getBytes(StandardCharsets.UTF_8), schema))
			.containsExactly(EnvelopeViolation.ACCESS_KEY_MISMATCH);
	}

}
