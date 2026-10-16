package com.example.einvoice.apikey;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Una key en la lista: nunca trae el secreto. {@code createdBy} es el correo de quien la creó. */
public record ApiKeyView(UUID id, String name, String publicId, String createdBy, OffsetDateTime createdAt,
		OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {
}
