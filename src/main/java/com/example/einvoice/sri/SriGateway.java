package com.example.einvoice.sri;

/**
 * Servicios off-line del SRI. Ninguna operación lanza por fallos de red o respuestas ilegibles:
 * devuelven un resultado FAILED, que quien llama trata como "no se sabe".
 */
public interface SriGateway {

	/** validarComprobante: envía el XML firmado. */
	ReceptionResult receive(SriEnvironment environment, byte[] signedXml);

	/** autorizacionComprobante: consulta el estado por clave de acceso. */
	AuthorizationResult authorize(SriEnvironment environment, String accessKey);

}
