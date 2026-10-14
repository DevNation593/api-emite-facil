package com.example.einvoice.signature;

import java.time.Instant;

import com.example.einvoice.certificate.SigningMaterial;

/**
 * Firma un comprobante. Es una interfaz para poder sustituir la implementación si el SRI rechaza
 * el resultado de la actual.
 */
public interface XmlSigner {

	/**
	 * Devuelve el XML con la firma añadida como último hijo del elemento raíz.
	 * @throws IllegalArgumentException si el XML no se puede leer, su raíz no tiene id="comprobante" o ya está firmado
	 */
	byte[] sign(byte[] unsignedXml, SigningMaterial material, Instant signingTime);

}
