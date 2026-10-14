package com.example.einvoice.certificate;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/** Clave privada y certificado con los que se firma un comprobante. Vive solo en memoria. */
public record SigningMaterial(PrivateKey privateKey, X509Certificate certificate) {
}
