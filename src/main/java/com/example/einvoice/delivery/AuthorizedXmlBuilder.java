package com.example.einvoice.delivery;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import com.example.einvoice.sri.SriEnvironment;

/**
 * XML autorizado que se entrega al receptor: los datos de la autorización del SRI y, dentro, el
 * comprobante firmado tal cual se envió. El comprobante va en una sección CDATA para que sus bytes
 * —y con ellos su firma— no cambien.
 */
public final class AuthorizedXmlBuilder {

	/** Hora de Ecuador continental, que es en la que el SRI expresa la fecha de autorización. */
	private static final ZoneOffset ECUADOR = ZoneOffset.ofHours(-5);

	private static final Pattern AUTHORIZATION_NUMBER = Pattern.compile("[0-9]{10,49}");

	private AuthorizedXmlBuilder() {
	}

	public static byte[] build(String authorizationNumber, OffsetDateTime authorizedAt, SriEnvironment environment,
			byte[] signedXml) {
		if (authorizationNumber == null || !AUTHORIZATION_NUMBER.matcher(authorizationNumber).matches()) {
			throw new IllegalArgumentException("Falta el número de autorización");
		}
		if (authorizedAt == null) {
			throw new IllegalArgumentException("Falta la fecha de autorización");
		}
		if (signedXml == null || signedXml.length == 0) {
			throw new IllegalArgumentException("Falta el comprobante firmado");
		}
		String invoice = new String(signedXml, StandardCharsets.UTF_8);
		StringBuilder xml = new StringBuilder(invoice.length() + 512);
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<autorizacion>\n");
		xml.append("<estado>AUTORIZADO</estado>\n");
		xml.append("<numeroAutorizacion>").append(authorizationNumber).append("</numeroAutorizacion>\n");
		xml.append("<fechaAutorizacion>")
			.append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(authorizedAt.withOffsetSameInstant(ECUADOR)))
			.append("</fechaAutorizacion>\n");
		xml.append("<ambiente>")
			.append(environment == SriEnvironment.PRODUCTION ? "PRODUCCIÓN" : "PRUEBAS")
			.append("</ambiente>\n");
		// "]]>" no puede aparecer dentro de una sección CDATA: se reparte entre dos secciones contiguas.
		xml.append("<comprobante><![CDATA[").append(invoice.replace("]]>", "]]]]><![CDATA[>")).append("]]></comprobante>\n");
		xml.append("<mensajes/>\n</autorizacion>\n");
		return xml.toString().getBytes(StandardCharsets.UTF_8);
	}

}
