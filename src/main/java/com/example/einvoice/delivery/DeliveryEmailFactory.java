package com.example.einvoice.delivery;

import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.RideEnvelope;
import com.example.einvoice.delivery.DeliveryEmail.Attachment;

/**
 * Redacta el correo de entrega de un comprobante autorizado, con el RIDE y el XML autorizado adjuntos
 * (ficha técnica, 4.7: el emisor debe enviar el comprobante al receptor por correo electrónico).
 */
public final class DeliveryEmailFactory {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final int MAX_NAME = 120;

	private static final int MAX_SUBJECT = 200;

	private DeliveryEmailFactory() {
	}

	public static DeliveryEmail build(String from, RideEnvelope ride, String displayName, String fileStem,
			String recipient, byte[] ridePdf, byte[] authorizedXml) {
		DocumentEnvelope document = ride.envelope();
		String number = document.number();
		String tradeName = headerSafe(document.issuer().tradeName(), MAX_NAME);
		String issuer = tradeName.isEmpty() ? headerSafe(document.issuer().legalName(), MAX_NAME) : tradeName;
		String subject = headerSafe(displayName + " " + number + " de " + issuer, MAX_SUBJECT);
		String total = document.totals() == null ? ""
				: "Valor total: " + document.totals().total().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
						+ " USD\n";
		String text = "Estimado(a) " + document.recipient().name() + ":\n\n" + document.issuer().legalName()
				+ " (RUC " + document.issuer().ruc() + ") le ha emitido la " + displayName.toLowerCase(Locale.ROOT)
				+ " electrónica " + number + ".\n\n" + "Fecha de emisión: " + DATE.format(document.issueDate()) + "\n"
				+ total + "Clave de acceso: " + document.accessKey() + "\n" + "Número de autorización: "
				+ ride.authorizationNumber() + "\n\n"
				+ "Se adjuntan el RIDE (PDF) y el comprobante autorizado (XML). Puede verificar su validez en el"
				+ " portal del SRI con la clave de acceso.\n";
		return new DeliveryEmail(from, issuer, recipient, subject, text,
				List.of(new Attachment(fileStem + "-" + number + ".pdf", "application/pdf", ridePdf),
						new Attachment(document.accessKey() + ".xml", "application/xml", authorizedXml)));
	}

	/**
	 * Texto apto para una cabecera del correo: sin caracteres de control (un salto de línea permitiría
	 * añadir cabeceras), con los espacios reducidos y de longitud acotada.
	 */
	static String headerSafe(String value, int max) {
		if (value == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(value.length());
		value.codePoints().forEach(codePoint -> {
			if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
				if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
					out.append(' ');
				}
			}
			else {
				out.appendCodePoint(codePoint);
			}
		});
		String clean = out.toString().trim();
		return clean.length() <= max ? clean : clean.substring(0, max).trim();
	}

}
