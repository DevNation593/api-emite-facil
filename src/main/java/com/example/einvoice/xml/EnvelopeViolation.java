package com.example.einvoice.xml;

/** Reglas del comprobante que el XSD del SRI acepta sin validar (docs/14-xsd-integration.md). */
public enum EnvelopeViolation {

	/** El elemento raíz no es el del tipo de comprobante del esquema. */
	ROOT_MISMATCH,

	COD_DOC_MISMATCH, VERSION_MISMATCH, ID_MISSING, SCHEMA_LOCATION_PRESENT,

	/** claveAcceso ausente, de longitud distinta de 49 dígitos o con dígito verificador incorrecto. */
	ACCESS_KEY_INVALID,

	/** claveAcceso bien formada pero que no corresponde a los datos del mismo comprobante. */
	ACCESS_KEY_MISMATCH

}
