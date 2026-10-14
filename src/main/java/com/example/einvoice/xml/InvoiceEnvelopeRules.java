package com.example.einvoice.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import com.example.einvoice.sequence.AccessKey;

/**
 * Comprueba en una factura lo que su XSD no restringe: código de documento, versión, id, ausencia
 * de schemaLocation y que la clave de acceso sea válida y corresponda a los datos del comprobante.
 * Se aplica a un XML que ya pasó {@link XsdValidator}.
 */
public final class InvoiceEnvelopeRules {

	private static final String INVOICE_DOCUMENT_CODE = "01";

	private static final String ROOT_ID = "comprobante";

	private InvoiceEnvelopeRules() {
	}

	public static Set<EnvelopeViolation> check(byte[] xml, SchemaVersion version) {
		EnvelopeHandler handler = new EnvelopeHandler();
		XMLReader reader = XsdValidator.newSecureReader();
		reader.setContentHandler(handler);
		reader.setErrorHandler(handler);
		try {
			reader.parse(new InputSource(new ByteArrayInputStream(xml)));
		}
		catch (SAXException | IOException ex) {
			throw new IllegalArgumentException("XML de factura ilegible: " + ex.getMessage(), ex);
		}
		Set<EnvelopeViolation> violations = EnumSet.noneOf(EnvelopeViolation.class);
		if (!INVOICE_DOCUMENT_CODE.equals(handler.header("infoTributaria/codDoc"))) {
			violations.add(EnvelopeViolation.COD_DOC_MISMATCH);
		}
		if (!version.value().equals(handler.version)) {
			violations.add(EnvelopeViolation.VERSION_MISMATCH);
		}
		if (!ROOT_ID.equals(handler.id)) {
			violations.add(EnvelopeViolation.ID_MISSING);
		}
		if (handler.schemaLocationPresent) {
			violations.add(EnvelopeViolation.SCHEMA_LOCATION_PRESENT);
		}
		String accessKey = handler.header("infoTributaria/claveAcceso");
		if (!AccessKey.valid(accessKey)) {
			violations.add(EnvelopeViolation.ACCESS_KEY_INVALID);
		}
		else if (!accessKey.equals(expectedAccessKey(handler, accessKey))) {
			violations.add(EnvelopeViolation.ACCESS_KEY_MISMATCH);
		}
		return violations;
	}

	/**
	 * Reconstruye la clave con los datos del comprobante y el código numérico de la clave declarada
	 * (doc 06). Devuelve nulo si falta algún dato o la fecha no tiene el formato dd/mm/aaaa.
	 */
	private static String expectedAccessKey(EnvelopeHandler handler, String declared) {
		String date = handler.header("infoFactura/fechaEmision");
		String[] parts = { handler.header("infoTributaria/codDoc"), handler.header("infoTributaria/ruc"),
				handler.header("infoTributaria/ambiente"), handler.header("infoTributaria/estab"),
				handler.header("infoTributaria/ptoEmi"), handler.header("infoTributaria/secuencial") };
		String emissionType = handler.header("infoTributaria/tipoEmision");
		if (date == null || !date.matches("[0-9]{2}/[0-9]{2}/[0-9]{4}") || emissionType == null) {
			return null;
		}
		StringBuilder base = new StringBuilder(date.replace("/", ""));
		for (String part : parts) {
			if (part == null) {
				return null;
			}
			base.append(part);
		}
		base.append(declared, 39, 47).append(emissionType);
		return base.toString().matches("[0-9]{48}") ? AccessKey.build(base.toString()) : null;
	}

	/** Recoge los atributos de la raíz y el texto de los elementos de cabecera, solo en su lugar exacto. */
	private static final class EnvelopeHandler extends DefaultHandler {

		private static final Set<String> HEADER_PATHS = Set.of("infoTributaria/codDoc", "infoTributaria/claveAcceso",
				"infoTributaria/ruc", "infoTributaria/ambiente", "infoTributaria/estab", "infoTributaria/ptoEmi",
				"infoTributaria/secuencial", "infoTributaria/tipoEmision", "infoFactura/fechaEmision");

		private final Deque<String> path = new ArrayDeque<>();

		private final Map<String, String> headers = new HashMap<>();

		private String capturing;

		private StringBuilder text;

		String version;

		String id;

		boolean schemaLocationPresent;

		String header(String headerPath) {
			return headers.get(headerPath);
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) {
			// El XSD admite atributos xsi:* en cualquier elemento, no solo en la raíz.
			if (attributes.getValue(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "schemaLocation") != null
					|| attributes.getValue(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,
							"noNamespaceSchemaLocation") != null) {
				schemaLocationPresent = true;
			}
			if (path.isEmpty()) {
				version = attributes.getValue("", "version");
				id = attributes.getValue("", "id");
			}
			else if (path.size() == 2 && uri.isEmpty()) {
				String candidate = path.peek() + "/" + localName;
				if (HEADER_PATHS.contains(candidate) && !headers.containsKey(candidate)) {
					capturing = candidate;
					text = new StringBuilder();
				}
			}
			path.push(localName);
		}

		@Override
		public void characters(char[] ch, int start, int length) {
			if (capturing != null && path.size() == 3) {
				text.append(ch, start, length);
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) {
			path.pop();
			if (capturing != null && path.size() == 2) {
				headers.put(capturing, text.toString());
				capturing = null;
				text = null;
			}
		}

	}

}
