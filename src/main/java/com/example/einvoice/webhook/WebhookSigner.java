package com.example.einvoice.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Firma de los avisos según la convención Standard Webhooks: HMAC-SHA256 sobre
 * "identificador.tiempo.cuerpo", con el secreto que solo conocen la aplicación y el receptor. El
 * tiempo entra en la firma para que un aviso capturado no se pueda repetir más tarde.
 */
public final class WebhookSigner {

	static final String PREFIX = "whsec_";

	private static final int KEY_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private WebhookSigner() {
	}

	/** Un secreto nuevo, tal como se le entrega a la empresa. */
	public static String newSecret() {
		byte[] key = new byte[KEY_BYTES];
		RANDOM.nextBytes(key);
		return PREFIX + Base64.getEncoder().encodeToString(key);
	}

	/** La clave que hay dentro de un secreto. */
	public static byte[] key(String secret) {
		if (secret == null || !secret.startsWith(PREFIX)) {
			throw new IllegalArgumentException("El secreto no tiene el formato esperado");
		}
		return Base64.getDecoder().decode(secret.substring(PREFIX.length()));
	}

	/** La firma, con su versión delante: "v1,..." */
	public static String sign(byte[] key, String id, long timestamp, byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
			return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
