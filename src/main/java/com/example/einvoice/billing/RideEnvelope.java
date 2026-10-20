package com.example.einvoice.billing;

import java.time.OffsetDateTime;

/** El sobre de un comprobante autorizado, con el número y la fecha de autorización que muestra el RIDE. */
public record RideEnvelope(DocumentEnvelope envelope, String authorizationNumber, OffsetDateTime authorizedAt) {
}
