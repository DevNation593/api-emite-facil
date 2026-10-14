package com.example.einvoice.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.Validator;

import org.springframework.stereotype.Component;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * Valida un XML contra el XSD de factura de una versión. Nunca lanza por contenido inválido y
 * rechaza cualquier DOCTYPE antes de resolver entidades (docs/10-security.md).
 */
@Component
public class XsdValidator {

	private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";

	private final InvoiceSchemas schemas;

	public XsdValidator(InvoiceSchemas schemas) {
		this.schemas = schemas;
	}

	public ValidationResult validate(byte[] xml, SchemaVersion version) {
		List<String> errors = new ArrayList<>();
		try {
			Validator validator = schemas.get(version).newValidator();
			validator.setErrorHandler(new CollectingErrorHandler(errors));
			validator.validate(new SAXSource(newSecureReader(), new InputSource(new ByteArrayInputStream(xml))));
		}
		catch (SAXParseException ex) {
			errors.add(describe(ex));
		}
		catch (SAXException | IOException ex) {
			errors.add(String.valueOf(ex.getMessage()));
		}
		return ValidationResult.of(errors);
	}

	static XMLReader newSecureReader() {
		try {
			SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature(DISALLOW_DOCTYPE, true);
			return factory.newSAXParser().getXMLReader();
		}
		catch (ParserConfigurationException | SAXException ex) {
			throw new IllegalStateException("El parser XML no admite la configuración segura requerida", ex);
		}
	}

	private static String describe(SAXParseException ex) {
		String message = String.valueOf(ex.getMessage());
		if (message.contains("DOCTYPE")) {
			return "DOCTYPE no permitido (línea " + ex.getLineNumber() + ")";
		}
		return message + " (línea " + ex.getLineNumber() + ")";
	}

	private record CollectingErrorHandler(List<String> errors) implements ErrorHandler {

		@Override
		public void warning(SAXParseException exception) {
		}

		@Override
		public void error(SAXParseException exception) {
			errors.add(describe(exception));
		}

		@Override
		public void fatalError(SAXParseException exception) throws SAXParseException {
			throw exception;
		}

	}

}
