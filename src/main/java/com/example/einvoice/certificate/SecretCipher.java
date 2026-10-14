package com.example.einvoice.certificate;

/**
 * Cifra secretos en reposo. El contexto se autentica junto con el secreto: un cifrado hecho para un
 * registro no se puede descifrar presentándolo como de otro.
 */
public interface SecretCipher {

	byte[] encrypt(byte[] plaintext, byte[] context);

	/** @throws SecretCipherException si el contenido fue alterado, el contexto no coincide o la clave es otra */
	byte[] decrypt(byte[] ciphertext, byte[] context);

}
