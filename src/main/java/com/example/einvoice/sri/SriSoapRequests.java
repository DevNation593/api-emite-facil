package com.example.einvoice.sri;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Peticiones SOAP 1.1 a los servicios off-line del SRI, según sus WSDL (cotejados el 2026-10-11):
 * document/literal, elementos hijos sin espacio de nombres y soapAction vacío.
 */
final class SriSoapRequests {

	static final String RECEPTION_NS = "http://ec.gob.sri.ws.recepcion";

	static final String AUTHORIZATION_NS = "http://ec.gob.sri.ws.autorizacion";

	private static final Pattern ACCESS_KEY = Pattern.compile("[0-9]{49}");

	private static final String OPEN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
			+ "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ec=\"";

	private SriSoapRequests() {
	}

	/** validarComprobante: el XML firmado viaja en Base64 (xs:base64Binary). */
	static byte[] reception(byte[] signedXml) {
		String body = OPEN + RECEPTION_NS + "\"><soapenv:Body><ec:validarComprobante><xml>"
				+ Base64.getEncoder().encodeToString(signedXml)
				+ "</xml></ec:validarComprobante></soapenv:Body></soapenv:Envelope>";
		return body.getBytes(StandardCharsets.UTF_8);
	}

	/** autorizacionComprobante por clave de acceso. */
	static byte[] authorization(String accessKey) {
		if (accessKey == null || !ACCESS_KEY.matcher(accessKey).matches()) {
			throw new IllegalArgumentException("La clave de acceso debe tener 49 dígitos");
		}
		String body = OPEN + AUTHORIZATION_NS + "\"><soapenv:Body><ec:autorizacionComprobante><claveAccesoComprobante>"
				+ accessKey + "</claveAccesoComprobante></ec:autorizacionComprobante></soapenv:Body></soapenv:Envelope>";
		return body.getBytes(StandardCharsets.UTF_8);
	}

}
