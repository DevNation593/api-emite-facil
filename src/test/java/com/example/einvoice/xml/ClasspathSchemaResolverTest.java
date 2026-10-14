package com.example.einvoice.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.w3c.dom.ls.LSInput;

class ClasspathSchemaResolverTest {

	static final String XSD = "http://www.w3.org/2001/XMLSchema";

	static final String DTD = "http://www.w3.org/TR/REC-xml";

	final ClasspathSchemaResolver resolver = new ClasspathSchemaResolver("xsd/invoice/2.1.0/");

	@Test
	void resolvesPackagedXmldsig() throws Exception {
		LSInput in = resolver.resolveResource(XSD, "http://www.w3.org/2000/09/xmldsig#", null,
				"xmldsig-core-schema.xsd", null);
		assertThat(in.getByteStream().readAllBytes()).hasSize(10292);
		assertThat(in.getSystemId()).endsWith("xsd/invoice/2.1.0/xmldsig-core-schema.xsd");
	}

	@Test
	void answersSchemaDtdWithEmptySubset() throws Exception {
		LSInput in = resolver.resolveResource(DTD, null, "-//W3C//DTD XMLSchema 200102//EN",
				"http://www.w3.org/2001/XMLSchema.dtd", null);
		assertThat(in.getByteStream().readAllBytes()).isEmpty();
	}

	@Test
	void rejectsEverythingElse() {
		assertThatThrownBy(() -> resolver.resolveResource(XSD, null, null, "http://example.com/evil.xsd", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> resolver.resolveResource(XSD, null, null, "../2.0.0/xmldsig-core-schema.xsd", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> resolver.resolveResource(DTD, null, "-//OTRO//DTD X//EN", "http://example.com/x.dtd", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
