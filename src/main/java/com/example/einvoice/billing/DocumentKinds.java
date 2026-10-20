package com.example.einvoice.billing;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.einvoice.xml.DocumentType;

/** Las piezas de cada tipo de comprobante habilitado, por tipo. Dos piezas para el mismo tipo no arrancan. */
@Component
public class DocumentKinds {

	private final Map<DocumentType, DocumentKind<?>> byType = new EnumMap<>(DocumentType.class);

	public DocumentKinds(List<DocumentKind<?>> kinds) {
		for (DocumentKind<?> kind : kinds) {
			if (byType.putIfAbsent(kind.type(), kind) != null) {
				throw new IllegalStateException("Hay dos implementaciones para el tipo de comprobante " + kind.type());
			}
		}
	}

	public Optional<DocumentKind<?>> find(DocumentType type) {
		return Optional.ofNullable(byType.get(type));
	}

}
