package com.example.einvoice.delivery;

import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.example.einvoice.delivery.DeliveryEmail.Attachment;
import com.example.einvoice.xml.InvoiceXmlData;

/**
 * Redacta el correo de entrega de una factura autorizada, con el RIDE y el XML autorizado adjuntos
 * (ficha técnica, 4.7: el emisor debe enviar el comprobante al receptor por correo electrónico).
 */
public final class DeliveryEmailFactory {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final int MAX_NAME = 120;

	private static final int MAX_SUBJECT = 200;

	private DeliveryEmailFactory() {
	}

	public static DeliveryEmail build(String from, RideData data, String recipient, byte[] ridePdf,
			byte[] authorizedXml) {
		InvoiceXmlData invoice = data.invoice();
		String number = invoice.establishmentCode() + "-" + invoice.emissionPointCode() + "-"
				+ String.format(Locale.ROOT, "%09d", invoice.sequence());
		String tradeName = headerSafe(invoice.issuerTradeName(), MAX_NAME);
		String issuer = tradeName.isEmpty() ? headerSafe(invoice.issuerLegalName(), MAX_NAME) : tradeName;
		String subject = headerSafe("Factura " + number + " de " + issuer, MAX_SUBJECT);
		String text = "Estimado(a) " + invoice.customerName() + ":\n\n" + invoice.issuerLegalName() + " (RUC "
				+ invoice.issuerRuc() + ") le ha emitido la factura electrónica " + number + ".\n\n"
				+ "Fecha de emisión: " + DATE.format(invoice.issueDate()) + "\n" + "Valor total: "
				+ invoice.total().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + " USD\n"
				+ "Clave de acceso: " + invoice.accessKey() + "\n" + "Número de autorización: "
				+ data.authorizationNumber() + "\n\n"
				+ "Se adjuntan el RIDE (PDF) y el comprobante autorizado (XML). Puede verificar su validez en el"
				+ " portal del SRI con la clave de acceso.\n";
		return new DeliveryEmail(from, issuer, recipient, subject, text,
				List.of(new Attachment("factura-" + number + ".pdf", "application/pdf", ridePdf),
						new Attachment(invoice.accessKey() + ".xml", "application/xml", authorizedXml)));
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
