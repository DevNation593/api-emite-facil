package com.example.einvoice.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Formato de las API keys: {@code efk_<publicId>_<secreto>}. El publicId (12 caracteres alfanuméricos)
 * identifica la clave y se puede guardar y mostrar; el secreto (43 caracteres base64 URL, 256 bits) solo
 * se entrega una vez y se guarda como SHA-256.
 *
 * <p>
 * Basta SHA-256, sin sal ni función lenta: el secreto sale de un generador criptográfico y tiene 256 bits
 * de entropía, así que no hay diccionario ni fuerza bruta que acelerar. Una función lenta como bcrypt solo
 * protege secretos que una persona elige y que pueden adivinarse.
 */
public final class ApiKeyCredential {

	public static final String PREFIX = "efk_";

	private static final int PUBLIC_ID_LENGTH = 12;

	private static final int SECRET_BYTES = 32;

	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

	private static final Pattern SHAPE = Pattern.compile("^efk_[A-Za-z0-9]{12}_[A-Za-z0-9_-]{43}$");

	private static final SecureRandom RANDOM = new SecureRandom();

	/** Clave recién creada: el valor completo se muestra una vez; en la base solo van publicId y hash. */
	public record Generated(String value, String publicId, String secretHash) {
	}

	/** Lo que llega en el encabezado, ya separado. */
	public record Presented(String publicId, String secret) {
	}

	private ApiKeyCredential() {
	}

	public static Generated generate() {
		StringBuilder id = new StringBuilder(PUBLIC_ID_LENGTH);
		for (int i = 0; i < PUBLIC_ID_LENGTH; i++) {
			id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		byte[] bytes = new byte[SECRET_BYTES];
		RANDOM.nextBytes(bytes);
		String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String publicId = id.toString();
		return new Generated(PREFIX + publicId + "_" + secret, publicId, hash(secret));
	}

	/**
	 * Separa el valor del encabezado. El secreto puede contener guiones bajos, por eso se corta por posición
	 * y no por el último guion bajo.
	 */
	public static Optional<Presented> parse(String headerValue) {
		if (headerValue == null) {
			return Optional.empty();
		}
		String value = headerValue.trim();
		if (!SHAPE.matcher(value).matches()) {
			return Optional.empty();
		}
		int idEnd = PREFIX.length() + PUBLIC_ID_LENGTH;
		return Optional.of(new Presented(value.substring(PREFIX.length(), idEnd), value.substring(idEnd + 1)));
	}

	/** SHA-256 en 64 caracteres hexadecimales en minúscula. */
	public static String hash(String secret) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Compara en tiempo constante el hash del secreto presentado con el guardado. */
	public static boolean matches(String secret, String storedHash) {
		return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII),
				storedHash.getBytes(StandardCharsets.US_ASCII));
	}

}
