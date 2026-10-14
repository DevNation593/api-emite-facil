package com.example.einvoice.xml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

/** Compila una vez cada XSD de factura empacado, sin acceso a red ni a archivos externos. */
@Component
public class InvoiceSchemas {

	private final Map<SchemaVersion, Schema> schemas = new ConcurrentHashMap<>();

	public Schema get(SchemaVersion version) {
		return schemas.computeIfAbsent(version, InvoiceSchemas::compile);
	}

	static SchemaFactory newSecureFactory() {
		try {
			SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			return factory;
		}
		catch (SAXException ex) {
			throw new IllegalStateException("La implementación de JAXP no admite restringir el acceso externo", ex);
		}
	}

	private static Schema compile(SchemaVersion version) {
		URL url = InvoiceSchemas.class.getClassLoader().getResource(version.xsdResource());
		if (url == null) {
			throw new IllegalStateException("XSD no empacado: " + version.xsdResource());
		}
		SchemaFactory factory = newSecureFactory();
		factory.setResourceResolver(new ClasspathSchemaResolver(version.resourceDir()));
		try (InputStream in = url.openStream()) {
			return factory.newSchema(new StreamSource(in, url.toExternalForm()));
		}
		catch (IOException | SAXException ex) {
			throw new IllegalStateException("No se pudo compilar el XSD de factura " + version.value(), ex);
		}
	}

}
