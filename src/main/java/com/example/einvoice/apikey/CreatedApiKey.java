package com.example.einvoice.apikey;

import java.time.OffsetDateTime;
import java.util.UUID;

/** La key recién creada. {@code key} es el valor completo: es la única vez que se entrega. */
public record CreatedApiKey(UUID id, String name, String publicId, String key, OffsetDateTime createdAt) {

	/** Sin el valor completo: el secreto no debe acabar en un registro por un toString. */
	@Override
	public String toString() {
		return "CreatedApiKey[id=" + id + ", name=" + name + ", publicId=" + publicId + "]";
	}

}
