package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentSchemas;
import com.example.einvoice.xml.InvoiceXmlBuilder;
import com.example.einvoice.xml.InvoiceXmlFixtures;
import com.example.einvoice.xml.XsdValidator;

class DocumentXmlCheckTest {

	private final DocumentXmlCheck check = new DocumentXmlCheck(new XsdValidator(new DocumentSchemas()));

	private final byte[] valid = InvoiceXmlBuilder.build(InvoiceXmlFixtures.standard());

	@Test
	void validXmlHasNoProblem() {
		assertThat(check.problem(valid, DocumentSchema.INVOICE_1_1_0)).isEmpty();
	}

	@Test
	void oversizedXmlIsReported() {
		assertThat(check.problem(new byte[321 * 1024], DocumentSchema.INVOICE_1_1_0)).hasValueSatisfying(
				message -> assertThat(message).contains("320 KB"));
	}

	@Test
	void envelopeViolationsAreReported() {
		byte[] wrongCode = new String(valid, StandardCharsets.UTF_8).replace("<codDoc>01</codDoc>", "<codDoc>04</codDoc>")
			.getBytes(StandardCharsets.UTF_8);
		assertThat(check.problem(wrongCode, DocumentSchema.INVOICE_1_1_0)).hasValueSatisfying(
				message -> assertThat(message).contains("COD_DOC_MISMATCH"));
	}

	@Test
	void schemaErrorsAreReported() {
		byte[] broken = new String(valid, StandardCharsets.UTF_8).replace("<razonSocial>", "<razonSocialX>")
			.replace("</razonSocial>", "</razonSocialX>")
			.getBytes(StandardCharsets.UTF_8);
		assertThat(check.problem(broken, DocumentSchema.INVOICE_1_1_0)).isPresent();
	}

}
