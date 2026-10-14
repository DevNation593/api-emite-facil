package com.example.einvoice.certificate;

/** El secreto no se pudo cifrar o descifrar. El mensaje nunca incluye material secreto. */
public class SecretCipherException extends RuntimeException {

	public SecretCipherException(String message, Throwable cause) {
		super(message, cause);
	}

}
