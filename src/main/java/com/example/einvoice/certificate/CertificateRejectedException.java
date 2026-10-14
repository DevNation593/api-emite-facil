package com.example.einvoice.certificate;

/** El certificado no sirve para firmar; errorCode dice por qué, sin datos del certificado ni de su clave. */
public class CertificateRejectedException extends RuntimeException {

	private final String errorCode;

	public CertificateRejectedException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}

}
