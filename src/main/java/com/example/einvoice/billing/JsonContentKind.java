package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

import tools.jackson.databind.json.JsonMapper;

/**
 * Base de los tipos cuyo contenido propio viaja como JSON en la columna content del sobre. Todo
 * {@code BigDecimal} de un record de contenido, también los anidados, se escribe como cadena JSON para que
 * vuelva con la misma escala con que se emitió.
 *
 * @param <C> record del contenido del tipo
 */
public abstract class JsonContentKind<C> implements DocumentKind<C> {

	private final Class<C> contentType;

	private final FiscalDocumentRepository documents;

	private final JsonMapper json;

	protected JsonContentKind(Class<C> contentType, FiscalDocumentRepository documents, JsonMapper json) {
		this.contentType = contentType;
		this.documents = documents;
		// Copia local: la configuración del mapper de la aplicación no cambia.
		this.json = json.rebuild()
			.withConfigOverride(BigDecimal.class,
					override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
			.build();
	}

	@Override
	public String contentJson(C content) {
		return json.writeValueAsString(content);
	}

	@Override
	public C loadContent(UUID tenantId, UUID documentId) {
		String content = documents.content(tenantId, documentId)
			.orElseThrow(() -> new IllegalStateException(
					"El comprobante " + documentId + " no existe o no tiene contenido propio"));
		return json.readValue(content, contentType);
	}

}
