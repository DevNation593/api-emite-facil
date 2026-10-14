package com.example.einvoice.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.xml.sax.SAXException;

class InvoiceSchemasTest {

	final InvoiceSchemas schemas = new InvoiceSchemas();

	@Test
	void factoryForbidsExternalAccess() throws Exception {
		SchemaFactory factory = InvoiceSchemas.newSecureFactory();
		assertThat(factory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING)).isTrue();
		// Sin el resolver local, el import de XMLDSig queda bloqueado por la restricción de acceso.
		URL xsd = getClass().getClassLoader().getResource(SchemaVersion.V2_1_0.xsdResource());
		assertThatThrownBy(() -> factory.newSchema(new StreamSource(xsd.toExternalForm())))
				.isInstanceOf(SAXException.class).hasMessageContaining("accessExternalSchema");
	}

	@Test
	void factoryForbidsExternalDtd() {
		SchemaFactory factory = InvoiceSchemas.newSecureFactory();
		// xmldsig-core-schema.xsd declara un DOCTYPE con un DTD en w3.org; sin resolver no debe leerse.
		URL xmldsig = getClass().getClassLoader()
			.getResource(SchemaVersion.V2_1_0.resourceDir() + "xmldsig-core-schema.xsd");
		assertThatThrownBy(() -> factory.newSchema(new StreamSource(xmldsig.toExternalForm())))
			.isInstanceOf(SAXException.class).hasMessageContaining("accessExternalDTD");
	}

	@ParameterizedTest
	@EnumSource(SchemaVersion.class)
	void everyVersionCompiles(SchemaVersion version) {
		assertThat(schemas.get(version)).isNotNull();
	}

	@Test
	void schemaIsCompiledOnce() {
		assertThat(schemas.get(SchemaVersion.V2_1_0)).isSameAs(schemas.get(SchemaVersion.V2_1_0));
	}

	@Test
	void ofRejectsUnknownVersion() {
		assertThat(SchemaVersion.of("2.1.0")).isEqualTo(SchemaVersion.V2_1_0);
		assertThatThrownBy(() -> SchemaVersion.of("3.0.0")).isInstanceOf(IllegalArgumentException.class);
	}

}
