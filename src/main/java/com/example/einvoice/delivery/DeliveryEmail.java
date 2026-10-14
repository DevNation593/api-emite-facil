package com.example.einvoice.delivery;

import java.util.List;

/** Correo con el que se entrega un comprobante: remitente, destinatario, texto y adjuntos. */
public record DeliveryEmail(String from, String fromName, String to, String subject, String text,
		List<Attachment> attachments) {

	public record Attachment(String filename, String contentType, byte[] content) {
	}

}
