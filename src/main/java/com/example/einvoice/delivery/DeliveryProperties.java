package com.example.einvoice.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * Configuración de la entrega de comprobantes. El correo está desactivado mientras no se habilite
 * expresamente; habilitado, exige un remitente válido para arrancar.
 */
@ConfigurationProperties("einvoice.delivery")
public record DeliveryProperties(Email email, Duration retryBackoff, Duration retryMaxBackoff, int retryMaxAttempts,
		Duration sendLease, int maxPerDocument) {

	/** from es solo la dirección, sin nombre: el nombre visible es el del emisor de cada comprobante. */
	public record Email(boolean enabled, String from) {
	}

	public DeliveryProperties {
		if (email == null) {
			email = new Email(false, null);
		}
		if (email.enabled() && !validAddress(email.from())) {
			throw new IllegalStateException(
					"einvoice.delivery.email.from debe ser una dirección de correo válida para habilitar el envío");
		}
		requirePositive("retry-backoff", retryBackoff);
		requirePositive("retry-max-backoff", retryMaxBackoff);
		requirePositive("send-lease", sendLease);
		if (retryMaxAttempts < 1) {
			throw new IllegalStateException("einvoice.delivery.retry-max-attempts debe ser al menos 1");
		}
		if (maxPerDocument < 1) {
			throw new IllegalStateException("einvoice.delivery.max-per-document debe ser al menos 1");
		}
	}

	/**
	 * Una sola dirección, sin nombre, comentarios ni caracteres de control. Se excluyen a propósito
	 * los caracteres con los que el formato de correo expresa varias direcciones en un solo valor
	 * (listas y grupos), que el servidor expandiría a varios destinatarios.
	 */
	public static boolean validAddress(String address) {
		if (address == null || address.isBlank() || address.length() > 254
				|| address.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)
						|| ",;:<>()\"".indexOf(c) >= 0)) {
			return false;
		}
		try {
			InternetAddress parsed = new InternetAddress(address, true);
			return !parsed.isGroup() && address.equals(parsed.getAddress());
		}
		catch (AddressException ex) {
			return false;
		}
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException("einvoice.delivery." + name + " debe ser mayor que cero");
		}
	}

}
