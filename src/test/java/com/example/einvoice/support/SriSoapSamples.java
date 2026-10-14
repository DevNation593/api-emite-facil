package com.example.einvoice.support;

/**
 * Respuestas SOAP del SRI con la forma de los ejemplos del numeral 7.2.3 de la ficha técnica v2.34,
 * parametrizadas por clave de acceso. Las usan las pruebas del lector de respuestas y el SRI simulado.
 */
public final class SriSoapSamples {

	public static final String AUTHORIZATION_DATE = "2012-03-05T16:57:34.997-05:00";

	private SriSoapSamples() {
	}

	public record Msg(String identifier, String text, String additionalInfo, String type) {

		public static Msg error(String identifier, String text) {
			return new Msg(identifier, text, null, "ERROR");
		}

		String xml() {
			return "<mensaje><identificador>" + identifier + "</identificador><mensaje>" + text + "</mensaje>"
					+ (additionalInfo == null ? "" : "<informacionAdicional>" + additionalInfo + "</informacionAdicional>")
					+ "<tipo>" + type + "</tipo></mensaje>";
		}

	}

	private static String envelope(String body) {
		return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" + body
				+ "</soap:Body></soap:Envelope>";
	}

	private static String reception(String content) {
		return envelope("<ns2:validarComprobanteResponse xmlns:ns2=\"http://ec.gob.sri.ws.recepcion\">"
				+ "<RespuestaRecepcionComprobante>" + content + "</RespuestaRecepcionComprobante>"
				+ "</ns2:validarComprobanteResponse>");
	}

	public static String received() {
		return reception("<estado>RECIBIDA</estado><comprobantes/>");
	}

	public static String returned(String accessKey, Msg... messages) {
		StringBuilder all = new StringBuilder();
		for (Msg message : messages) {
			all.append(message.xml());
		}
		return reception("<estado>DEVUELTA</estado><comprobantes><comprobante><claveAcceso>" + accessKey
				+ "</claveAcceso><mensajes>" + all + "</mensajes></comprobante></comprobantes>");
	}

	/** Respuesta de recepción con un estado cualquiera, para los casos que el contrato no prevé. */
	public static String receptionWithState(String state) {
		return reception("<estado>" + state + "</estado><comprobantes/>");
	}

	/** Un elemento autorizacion. Los datos nulos se omiten. */
	public static String autorizacion(String state, String number, String date, String environment, Msg... messages) {
		StringBuilder all = new StringBuilder();
		for (Msg message : messages) {
			all.append(message.xml());
		}
		return "<autorizacion><estado>" + state + "</estado>"
				+ (number == null ? "" : "<numeroAutorizacion> " + number + " </numeroAutorizacion>")
				+ (date == null ? "" : "<fechaAutorizacion>" + date + "</fechaAutorizacion>")
				+ (environment == null ? "" : "<ambiente>" + environment + "</ambiente>")
				+ "<comprobante><![CDATA[<?xml version=\"1.0\" encoding=\"UTF-8\"?><factura id=\"comprobante\""
				+ " version=\"1.1.0\"></factura>]]></comprobante><mensajes>" + all + "</mensajes></autorizacion>";
	}

	public static String authorizations(String consultedAccessKey, String... items) {
		return envelope("<ns2:autorizacionComprobanteResponse xmlns:ns2=\"http://ec.gob.sri.ws.autorizacion\">"
				+ "<RespuestaAutorizacionComprobante><claveAccesoConsultada> " + consultedAccessKey
				+ " </claveAccesoConsultada><numeroComprobantes>" + items.length + "</numeroComprobantes>"
				+ (items.length == 0 ? "<autorizaciones/>" : "<autorizaciones>" + String.join("", items) + "</autorizaciones>")
				+ "</RespuestaAutorizacionComprobante></ns2:autorizacionComprobanteResponse>");
	}

	/** Comprobante autorizado en el ambiente de pruebas, con la advertencia 60 que el SRI añade en él. */
	public static String authorized(String accessKey) {
		return authorizedIn(accessKey, "PRUEBAS");
	}

	public static String authorizedIn(String accessKey, String environment) {
		return authorizations(accessKey,
				autorizacion("AUTORIZADO", accessKey, AUTHORIZATION_DATE, environment, new Msg("60",
						"ESTE PROCESO FUE REALIZADO EN EL AMBIENTE DE PRUEBAS", null, "ADVERTENCIA")));
	}

	public static String notAuthorized(String accessKey, String state, Msg... messages) {
		return authorizations(accessKey,
				autorizacion(state, null, "2012-02-13T16:34:48.997-05:00", "PRUEBAS", messages));
	}

	public static String processing(String accessKey) {
		return authorizations(accessKey, autorizacion("EN PROCESO", null, null, "PRUEBAS"));
	}

	public static String notFound(String accessKey) {
		return authorizations(accessKey);
	}

	/** Respuesta de autorización vacía: ni clave consultada ni autorizaciones. */
	public static String emptyAuthorizationResponse() {
		return envelope("<ns2:autorizacionComprobanteResponse xmlns:ns2=\"http://ec.gob.sri.ws.autorizacion\">"
				+ "<RespuestaAutorizacionComprobante/></ns2:autorizacionComprobanteResponse>");
	}

	public static String fault() {
		return envelope("<soap:Fault><faultcode>soap:Server</faultcode><faultstring>Error interno</faultstring>"
				+ "</soap:Fault>");
	}

}
