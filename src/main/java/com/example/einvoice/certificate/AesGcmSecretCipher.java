package com.example.einvoice.certificate;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifrado de sobre con AES-256-GCM: cada secreto se cifra con una clave de datos aleatoria, y esa
 * clave se guarda envuelta con la clave maestra. La clave maestra llega por configuración; es un
 * sustituto local de un KMS y en producción debe venir de un gestor de claves.
 *
 * Formato: versión (1 byte) | IV de envoltura (12) | clave de datos envuelta (32 + 16) | IV de datos
 * (12) | secreto cifrado con su etiqueta.
 */
@Component
public class AesGcmSecretCipher implements SecretCipher {

	private static final byte FORMAT_VERSION = 1;

	private static final int KEY_BYTES = 32;

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final int WRAPPED_KEY_BYTES = KEY_BYTES + TAG_BITS / 8;

	private final SecretKeySpec masterKey;

	private final SecureRandom random = new SecureRandom();

	public AesGcmSecretCipher(@Value("${einvoice.crypto.master-key}") String masterKeyBase64) {
		byte[] key;
		try {
			key = Base64.getDecoder().decode(masterKeyBase64.strip());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("einvoice.crypto.master-key no es Base64 válido");
		}
		if (key.length != KEY_BYTES) {
			throw new IllegalStateException("einvoice.crypto.master-key debe decodificar a 32 bytes");
		}
		this.masterKey = new SecretKeySpec(key, "AES");
	}

	@Override
	public byte[] encrypt(byte[] plaintext, byte[] context) {
		try {
			byte[] dataKey = new byte[KEY_BYTES];
			random.nextBytes(dataKey);
			byte[] wrapIv = randomIv();
			byte[] wrappedKey = gcm(Cipher.ENCRYPT_MODE, masterKey, wrapIv, context, dataKey);
			byte[] dataIv = randomIv();
			byte[] encrypted = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), dataIv, context, plaintext);
			return ByteBuffer.allocate(1 + IV_BYTES + wrappedKey.length + IV_BYTES + encrypted.length)
				.put(FORMAT_VERSION)
				.put(wrapIv)
				.put(wrappedKey)
				.put(dataIv)
				.put(encrypted)
				.array();
		}
		catch (GeneralSecurityException ex) {
			throw new SecretCipherException("No se pudo cifrar el secreto", ex);
		}
	}

	@Override
	public byte[] decrypt(byte[] ciphertext, byte[] context) {
		int minimum = 1 + IV_BYTES + WRAPPED_KEY_BYTES + IV_BYTES + TAG_BITS / 8;
		if (ciphertext == null || ciphertext.length < minimum || ciphertext[0] != FORMAT_VERSION) {
			throw new SecretCipherException("El secreto cifrado no tiene el formato esperado", null);
		}
		try {
			ByteBuffer buffer = ByteBuffer.wrap(ciphertext, 1, ciphertext.length - 1);
			byte[] wrapIv = take(buffer, IV_BYTES);
			byte[] wrappedKey = take(buffer, WRAPPED_KEY_BYTES);
			byte[] dataIv = take(buffer, IV_BYTES);
			byte[] encrypted = take(buffer, buffer.remaining());
			byte[] dataKey = gcm(Cipher.DECRYPT_MODE, masterKey, wrapIv, context, wrappedKey);
			return gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"), dataIv, context, encrypted);
		}
		catch (GeneralSecurityException ex) {
			throw new SecretCipherException("El secreto cifrado no se pudo descifrar", ex);
		}
	}

	private static byte[] gcm(int mode, SecretKeySpec key, byte[] iv, byte[] context, byte[] input)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
		cipher.updateAAD(context);
		return cipher.doFinal(input);
	}

	private byte[] randomIv() {
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		return iv;
	}

	private static byte[] take(ByteBuffer buffer, int length) {
		byte[] bytes = new byte[length];
		buffer.get(bytes);
		return bytes;
	}

}
