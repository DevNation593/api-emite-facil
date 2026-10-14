package com.example.einvoice.webhook;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A qué direcciones puede llamar la aplicación por encargo de un cliente. La dirección la escribe
 * él, así que no se confía en ella: solo https y solo servidores de la internet pública. Si no,
 * serviría para alcanzar desde dentro lo que desde fuera no se ve (otros servicios, la consola de
 * la nube, la propia base).
 *
 * <p>{@link #vet} devuelve la dirección ya comprobada, y es a esa a la que hay que conectarse: volver
 * a resolver el nombre al enviar dejaría que cambiara entre la comprobación y el uso.
 */
@Component
public class WebhookUrlPolicy {

	/** Cómo se resuelve un nombre; las pruebas ponen el suyo. */
	@FunctionalInterface
	public interface Resolver {

		InetAddress[] resolve(String host) throws UnknownHostException;

	}

	static final int MAX_LENGTH = 2048;

	private final boolean allowInsecureHttp;

	private final boolean allowPrivateAddresses;

	private final Resolver resolver;

	@Autowired
	public WebhookUrlPolicy(WebhookProperties properties) {
		this(properties.allowInsecureHttp(), properties.allowPrivateAddresses(), InetAddress::getAllByName);
	}

	public WebhookUrlPolicy(boolean allowInsecureHttp, boolean allowPrivateAddresses, Resolver resolver) {
		this.allowInsecureHttp = allowInsecureHttp;
		this.allowPrivateAddresses = allowPrivateAddresses;
		this.resolver = resolver;
	}

	/** Comprueba la forma de la dirección, sin resolver su nombre. */
	public URI parse(String url) {
		if (url == null || url.isBlank() || url.length() > MAX_LENGTH) {
			throw new WebhookUrlException("La dirección debe tener entre 1 y " + MAX_LENGTH + " caracteres");
		}
		if (!url.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
			// La petición se escribe en ASCII: una tilde sin codificar llegaría convertida en otra cosa.
			throw new WebhookUrlException(
					"La dirección solo puede llevar letras sin tilde, dígitos y signos; codifique lo demás con %");
		}
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException ex) {
			throw new WebhookUrlException("La dirección no está bien escrita");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
		if (!scheme.equals("https") && !(allowInsecureHttp && scheme.equals("http"))) {
			throw new WebhookUrlException("La dirección debe empezar por https://");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new WebhookUrlException("La dirección no indica un servidor válido");
		}
		if (uri.getHost().endsWith(".")) {
			throw new WebhookUrlException("El nombre del servidor no puede acabar en punto");
		}
		if (uri.getRawUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
			throw new WebhookUrlException("La dirección no puede llevar usuario ni contraseña");
		}
		if (uri.getRawFragment() != null) {
			throw new WebhookUrlException("La dirección no puede llevar fragmento (#)");
		}
		if (uri.getPort() == 0 || uri.getPort() > 65535) {
			throw new WebhookUrlException("El puerto de la dirección no es válido");
		}
		return uri;
	}

	/**
	 * Resuelve el servidor y devuelve la dirección a la que conectarse. La rechaza si alguna de las
	 * direcciones a las que resuelve no es de la internet pública.
	 */
	public InetAddress vet(URI url) {
		String host = url.getHost();
		InetAddress[] addresses;
		try {
			// Un literal IPv6 viene entre corchetes.
			addresses = resolver.resolve(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
		}
		catch (UnknownHostException ex) {
			throw new WebhookUrlException("No se encontró el servidor de la dirección", true);
		}
		if (addresses == null || addresses.length == 0) {
			throw new WebhookUrlException("No se encontró el servidor de la dirección", true);
		}
		if (!allowPrivateAddresses) {
			for (InetAddress address : addresses) {
				if (!isPublic(address)) {
					throw new WebhookUrlException(
							"La dirección debe ser de un servidor público de internet, no de una red local o privada");
				}
			}
		}
		return addresses[0];
	}

	/** Si la dirección es de la internet pública: ni local, ni privada, ni reservada. */
	public static boolean isPublic(InetAddress address) {
		byte[] raw = address.getAddress();
		if (address instanceof Inet4Address) {
			return isPublicV4(raw[0] & 0xff, raw[1] & 0xff, raw[2] & 0xff);
		}
		int first = raw[0] & 0xff;
		int second = raw[1] & 0xff;
		// De IPv6 solo vale el rango de direcciones globales (2000::/3)...
		if ((first & 0xe0) != 0x20) {
			return false;
		}
		// ...menos lo que dentro de él está reservado o lleva dentro una dirección IPv4:
		// 2001::/23 (protocolos, incluido Teredo), 2001:db8::/32 (documentación) y 2002::/16 (6to4).
		boolean protocols = first == 0x20 && second == 0x01 && (raw[2] & 0xfe) == 0;
		boolean documentation = first == 0x20 && second == 0x01 && (raw[2] & 0xff) == 0x0d && (raw[3] & 0xff) == 0xb8;
		boolean sixToFour = first == 0x20 && second == 0x02;
		return !protocols && !documentation && !sixToFour;
	}

	private static boolean isPublicV4(int a, int b, int c) {
		return !(a == 0 // "esta red"
				|| a == 10 // privada
				|| (a == 100 && b >= 64 && b <= 127) // compartida entre operadores (CGNAT)
				|| a == 127 // la propia máquina
				|| (a == 169 && b == 254) // enlace local: aquí viven los metadatos de las nubes
				|| (a == 172 && b >= 16 && b <= 31) // privada
				|| (a == 192 && b == 0 && c == 0) // asignaciones de protocolo
				|| (a == 192 && b == 0 && c == 2) // documentación
				|| (a == 192 && b == 88 && c == 99) // relevo 6to4
				|| (a == 192 && b == 168) // privada
				|| (a == 198 && (b == 18 || b == 19)) // pruebas de rendimiento
				|| (a == 198 && b == 51 && c == 100) // documentación
				|| (a == 203 && b == 0 && c == 113) // documentación
				|| a >= 224); // multidifusión, reservadas y difusión
	}

}
