package com.example.einvoice.sri;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.example.einvoice.xml.SecureXml;

/**
 * Lee las respuestas SOAP de recepción y autorización del SRI (ficha técnica v2.34, numeral 7.2.3).
 * Nunca lanza: lo que no se entiende es un resultado FAILED con su explicación, no un estado inventado.
 * Los elementos se buscan por nombre local: el WSDL los declara por referencia, pero el SRI los
 * devuelve sin espacio de nombres.
 */
public final class SriResponseParser {

	private static final int MAX_MESSAGES = 100;

	private static final int MAX_TEXT = 2000;

	private static final int MAX_STATUS = 60;

	private static final Pattern AUTHORIZATION_NUMBER = Pattern.compile("[0-9]{10,49}");

	/** Hora de Ecuador continental, para las fechas que llegan sin desplazamiento. */
	private static final ZoneOffset ECUADOR = ZoneOffset.ofHours(-5);

	private SriResponseParser() {
	}

	public static ReceptionResult reception(byte[] body) {
		Element response;
		try {
			response = responseElement(body, "RespuestaRecepcionComprobante");
		}
		catch (UnreadableResponse ex) {
			return ReceptionResult.failed(null, ex.getMessage(), null);
		}
		String status = text(response, "estado");
		List<SriMessage> messages = new ArrayList<>();
		Element vouchers = child(response, "comprobantes");
		if (vouchers != null) {
			for (Element voucher : children(vouchers, "comprobante")) {
				addMessages(voucher, messages);
			}
		}
		ReceptionResult.Outcome outcome;
		String error = null;
		if ("RECIBIDA".equalsIgnoreCase(status)) {
			outcome = ReceptionResult.Outcome.RECEIVED;
		}
		else if ("DEVUELTA".equalsIgnoreCase(status)) {
			outcome = ReceptionResult.Outcome.RETURNED;
		}
		else {
			outcome = ReceptionResult.Outcome.FAILED;
			error = status == null ? "La respuesta de recepción no trae estado" : "Estado de recepción desconocido";
		}
		return new ReceptionResult(outcome, bounded(status, MAX_STATUS), List.copyOf(messages), null, error, null);
	}

	public static AuthorizationResult authorization(byte[] body) {
		Element response;
		try {
			response = responseElement(body, "RespuestaAutorizacionComprobante");
		}
		catch (UnreadableResponse ex) {
			return AuthorizationResult.failed(null, ex.getMessage(), null);
		}
		String consulted = text(response, "claveAccesoConsultada");
		Element list = child(response, "autorizaciones");
		List<Element> entries = list == null ? List.of() : children(list, "autorizacion");
		if (entries.isEmpty()) {
			return new AuthorizationResult(AuthorizationResult.Outcome.NOT_FOUND, null, consulted, null, null, null,
					List.of(), null, null, null);
		}
		// Puede haber varias respuestas no autorizadas y una sola autorizada: esa manda.
		for (Element entry : entries) {
			if ("AUTORIZADO".equalsIgnoreCase(text(entry, "estado"))) {
				return authorized(entry, consulted);
			}
		}
		Element latest = entries.get(0);
		OffsetDateTime latestDate = date(text(latest, "fechaAutorizacion"));
		for (Element entry : entries) {
			OffsetDateTime entryDate = date(text(entry, "fechaAutorizacion"));
			if (entryDate != null && (latestDate == null || entryDate.isAfter(latestDate))) {
				latest = entry;
				latestDate = entryDate;
			}
		}
		String status = text(latest, "estado");
		String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
		boolean rejected = normalized.equals("NO AUTORIZADO") || normalized.equals("RECHAZADO");
		List<SriMessage> messages = new ArrayList<>();
		addMessages(latest, messages);
		return new AuthorizationResult(
				rejected ? AuthorizationResult.Outcome.NOT_AUTHORIZED : AuthorizationResult.Outcome.PROCESSING,
				bounded(status, MAX_STATUS), consulted, null, latestDate, text(latest, "ambiente"),
				List.copyOf(messages), null, null, null);
	}

	private static AuthorizationResult authorized(Element entry, String consulted) {
		String status = bounded(text(entry, "estado"), MAX_STATUS);
		String number = text(entry, "numeroAutorizacion");
		OffsetDateTime date = date(text(entry, "fechaAutorizacion"));
		List<SriMessage> messages = new ArrayList<>();
		addMessages(entry, messages);
		String error = null;
		if (number == null || !AUTHORIZATION_NUMBER.matcher(number).matches()) {
			error = "La autorización no trae un número de autorización válido";
		}
		else if (date == null) {
			error = "La autorización no trae una fecha legible";
		}
		if (error != null) {
			return new AuthorizationResult(AuthorizationResult.Outcome.FAILED, status, consulted, null, null,
					text(entry, "ambiente"), List.copyOf(messages), null, error, null);
		}
		return new AuthorizationResult(AuthorizationResult.Outcome.AUTHORIZED, status, consulted, number, date,
				text(entry, "ambiente"), List.copyOf(messages), null, null, null);
	}

	private static final class UnreadableResponse extends Exception {

		private static final long serialVersionUID = 1L;

		UnreadableResponse(String message) {
			super(message);
		}

	}

	private static Element responseElement(byte[] body, String localName) throws UnreadableResponse {
		if (body == null || body.length == 0) {
			throw new UnreadableResponse("La respuesta del SRI llegó vacía");
		}
		Document document;
		try {
			document = SecureXml.parse(body);
		}
		catch (SAXException | IOException ex) {
			throw new UnreadableResponse("La respuesta del SRI no es un XML legible");
		}
		if (document.getElementsByTagNameNS("*", "Fault").getLength() > 0) {
			throw new UnreadableResponse("El SRI respondió con un error SOAP (Fault)");
		}
		NodeList found = document.getElementsByTagNameNS("*", localName);
		if (found.getLength() == 0) {
			found = document.getElementsByTagName(localName);
		}
		if (found.getLength() != 1) {
			throw new UnreadableResponse("La respuesta del SRI no contiene " + localName);
		}
		return (Element) found.item(0);
	}

	private static void addMessages(Element parent, List<SriMessage> into) {
		Element list = child(parent, "mensajes");
		if (list == null) {
			return;
		}
		for (Element message : children(list, "mensaje")) {
			if (into.size() == MAX_MESSAGES) {
				return;
			}
			into.add(new SriMessage(bounded(text(message, "identificador"), 20),
					bounded(text(message, "mensaje"), MAX_TEXT),
					bounded(text(message, "informacionAdicional"), MAX_TEXT), bounded(text(message, "tipo"), 30)));
		}
	}

	private static OffsetDateTime date(String text) {
		if (text == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(text);
		}
		catch (DateTimeParseException withoutOffset) {
			try {
				return LocalDateTime.parse(text).atOffset(ECUADOR);
			}
			catch (DateTimeParseException ex) {
				return null;
			}
		}
	}

	private static List<Element> children(Element parent, String localName) {
		List<Element> result = new ArrayList<>();
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && localName.equals(localName(element))) {
				result.add(element);
			}
		}
		return result;
	}

	private static Element child(Element parent, String localName) {
		List<Element> found = children(parent, localName);
		return found.isEmpty() ? null : found.get(0);
	}

	/** Texto del hijo, sin espacios alrededor; nulo si no existe o está vacío. */
	private static String text(Element parent, String localName) {
		Element element = child(parent, localName);
		if (element == null) {
			return null;
		}
		String value = element.getTextContent().trim();
		return value.isEmpty() ? null : value;
	}

	private static String localName(Element element) {
		return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
	}

	private static String bounded(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

}
