package com.example.einvoice.delivery;

import java.time.OffsetDateTime;
import java.util.Map;

import com.example.einvoice.xml.InvoiceXmlData;

/**
 * Lo que el RIDE de una factura autorizada muestra: los datos congelados del comprobante, su
 * autorización, el correo y el teléfono del cliente y el teléfono del emisor (los tres pueden ser nulos:
 * no van en el XML) y el nombre de cada forma de pago por código.
 */
public record RideData(InvoiceXmlData invoice, String authorizationNumber, OffsetDateTime authorizedAt,
		String customerEmail, String customerPhone, String issuerPhone, Map<String, String> paymentMethodNames) {
}
