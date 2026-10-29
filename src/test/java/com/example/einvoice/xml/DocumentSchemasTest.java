package com.example.einvoice.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.net.URL;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.xml.sax.SAXException;

class DocumentSchemasTest {

	final DocumentSchemas schemas = new DocumentSchemas();

	@Test
	void factoryForbidsExternalAccess() throws Exception {
		SchemaFactory factory = DocumentSchemas.newSecureFactory();
		assertThat(factory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING)).isTrue();
		// Sin el resolver local, el import de XMLDSig queda bloqueado por la restricción de acceso.
		URL xsd = getClass().getClassLoader().getResource(DocumentSchema.INVOICE_2_1_0.xsdResource());
		assertThatThrownBy(() -> factory.newSchema(new StreamSource(xsd.toExternalForm())))
				.isInstanceOf(SAXException.class).hasMessageContaining("accessExternalSchema");
	}

	@Test
	void factoryForbidsExternalDtd() {
		SchemaFactory factory = DocumentSchemas.newSecureFactory();
		// Un DTD fuera del catálogo del JDK. El de xmldsig-core-schema.xsd (XMLSchema.dtd de w3.org) no
		// sirve: desde Java 24 el JDK trae una copia y la lee de ahí, sin red y sin pasar por esta restricción.
		String xsd = """
				<?xml version="1.0"?>
				<!DOCTYPE schema SYSTEM "https://ejemplo.invalid/externo.dtd">
				<schema xmlns="http://www.w3.org/2001/XMLSchema"/>
				""";
		assertThatThrownBy(() -> factory.newSchema(new StreamSource(new StringReader(xsd))))
			.isInstanceOf(SAXException.class).hasMessageContaining("accessExternalDTD");
	}

	@ParameterizedTest
	@EnumSource(DocumentSchema.class)
	void everySchemaCompiles(DocumentSchema schema) {
		assertThat(schemas.get(schema)).isNotNull();
	}

	@Test
	void schemaIsCompiledOnce() {
		assertThat(schemas.get(DocumentSchema.INVOICE_2_1_0)).isSameAs(schemas.get(DocumentSchema.INVOICE_2_1_0));
		assertThat(schemas.get(SchemaVersion.V2_1_0)).isSameAs(schemas.get(DocumentSchema.INVOICE_2_1_0));
	}

	@Test
	void ofRejectsUnknownVersion() {
		assertThat(SchemaVersion.of("2.1.0")).isEqualTo(SchemaVersion.V2_1_0);
		assertThatThrownBy(() -> SchemaVersion.of("3.0.0")).isInstanceOf(IllegalArgumentException.class);
		assertThat(DocumentSchema.of(DocumentType.RETENTION_RECEIPT, "2.0.0"))
			.isEqualTo(DocumentSchema.RETENTION_RECEIPT_2_0_0);
		// La nota de débito solo se publica en 1.0.0.
		assertThatThrownBy(() -> DocumentSchema.of(DocumentType.DEBIT_NOTE, "1.1.0"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@EnumSource(SchemaVersion.class)
	void invoiceVersionsPointToInvoiceSchemas(SchemaVersion version) {
		assertThat(version.schema().type()).isEqualTo(DocumentType.INVOICE);
		assertThat(version.schema().version()).isEqualTo(version.value());
	}

	@Test
	void resourcesFollowTheTypeFolderAndFileName() {
		assertThat(DocumentSchema.CREDIT_NOTE_1_1_0.xsdResource())
			.isEqualTo("xsd/credit-note/1.1.0/NotaCredito_V1.1.0.xsd");
		assertThat(DocumentSchema.INVOICE_1_0_0.sampleResource()).isEqualTo("xsd/invoice/1.0.0/factura_V1.0.0.xml");
	}

	@Test
	void documentTypesCarryTheSriCode() {
		assertThat(DocumentType.of("07")).isEqualTo(DocumentType.RETENTION_RECEIPT);
		assertThat(DocumentType.PURCHASE_LIQUIDATION.code()).isEqualTo("03");
		assertThatThrownBy(() -> DocumentType.of("02")).isInstanceOf(IllegalArgumentException.class);
	}

}
