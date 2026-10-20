package com.example.einvoice.billing;

import java.util.UUID;

import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentType;

/**
 * Un tipo de comprobante como pieza: qué esquema emite, cómo se llama, cómo guarda y lee su contenido
 * propio y cómo genera su XML y su RIDE a partir del sobre común. Hay una implementación por tipo
 * habilitado; {@link DocumentKinds} las reúne.
 *
 * @param <C> el contenido propio del tipo
 */
public interface DocumentKind<C> {

	/** Código del comprobante: 01, 03, 04, 05, 06 o 07. */
	DocumentType type();

	/** Versión del XSD con que se emite. */
	DocumentSchema schema();

	/** Nombre para el correo, el PDF y la descarga: «Factura», «Nota de crédito»… */
	String displayName();

	/** Raíz del nombre de los archivos: «factura», «nota-credito»… */
	String fileStem();

	/** Acción de auditoría al reservar el comprobante: INVOICE_RESERVED, CREDIT_NOTE_RESERVED… */
	String auditReserved();

	/** JSON para la columna content, que va en el mismo INSERT del sobre; nulo para la factura. */
	String contentJson(C content);

	/**
	 * El contenido tal como el worker lo leerá después de guardarlo. Los tipos que guardan JSON lo pasan por
	 * su ida y vuelta; la factura, que no usa JSON, lo devuelve igual.
	 */
	default C reloaded(C content) {
		return content;
	}

	/** Lo que el tipo guarda en tablas propias tras insertar el sobre; la factura, su cuerpo. */
	default void saveDetails(UUID tenantId, UUID documentId, C content) {
	}

	/** Contenido propio del comprobante ya guardado. */
	C loadContent(UUID tenantId, UUID documentId);

	/** XML sin firmar del comprobante. */
	byte[] buildXml(DocumentEnvelope envelope, C content);

	/** PDF del RIDE del comprobante autorizado. */
	byte[] buildRide(RideEnvelope ride, C content);

}
