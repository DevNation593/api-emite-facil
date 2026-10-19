package com.example.einvoice.xml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Los XML de ejemplo de los comprobantes distintos de la factura (la factura la cubre InvoiceXsdIT). */
class DocumentSamplesXsdIT {

	final XsdValidator validator = new XsdValidator(new DocumentSchemas());

	static byte[] sample(DocumentSchema schema) throws IOException {
		try (var in = DocumentSamplesXsdIT.class.getClassLoader().getResourceAsStream(schema.sampleResource())) {
			return in.readAllBytes();
		}
	}

	@ParameterizedTest
	@EnumSource(value = DocumentSchema.class, mode = EnumSource.Mode.MATCH_NONE, names = "INVOICE_.*")
	void sampleValidates(DocumentSchema schema) throws IOException {
		ValidationResult result = validator.validate(sample(schema), schema);
		assertThat(result.errors()).isEmpty();
		assertThat(result.valid()).isTrue();
	}

}
