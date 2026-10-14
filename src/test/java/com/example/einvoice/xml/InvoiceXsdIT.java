package com.example.einvoice.xml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InvoiceXsdIT {

	final XsdValidator validator = new XsdValidator(new InvoiceSchemas());

	static byte[] sample(SchemaVersion version) throws IOException {
		try (var in = InvoiceXsdIT.class.getClassLoader().getResourceAsStream(version.sampleResource())) {
			return in.readAllBytes();
		}
	}

	@ParameterizedTest
	@EnumSource(value = SchemaVersion.class, names = { "V1_0_0", "V1_1_0", "V2_1_0" })
	void officialSampleValidates(SchemaVersion version) throws IOException {
		ValidationResult result = validator.validate(sample(version), version);
		assertThat(result.errors()).isEmpty();
		assertThat(result.valid()).isTrue();
	}

	@Test
	void sample200FailsOnEmptyIncoterm() throws IOException {
		ValidationResult result = validator.validate(sample(SchemaVersion.V2_0_0), SchemaVersion.V2_0_0);
		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).anyMatch(e -> e.contains("incoTermFactura"));
	}

	@Test
	void rejectsDoctypeWithoutReadingExternalEntity() {
		String xml = "<?xml version=\"1.0\"?><!DOCTYPE factura [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
				+ "<factura id=\"comprobante\" version=\"1.0.0\">&x;</factura>";
		ValidationResult result = validator.validate(xml.getBytes(StandardCharsets.UTF_8), SchemaVersion.V1_0_0);
		assertThat(result.valid()).isFalse();
		assertThat(result.errors().get(0)).startsWith("DOCTYPE no permitido");
	}

	@Test
	void malformedOrEmptyXmlIsInvalidNotAnException() {
		assertThat(validator.validate("<factura".getBytes(StandardCharsets.UTF_8), SchemaVersion.V1_0_0).valid())
				.isFalse();
		assertThat(validator.validate(new byte[0], SchemaVersion.V1_0_0).valid()).isFalse();
	}

}
