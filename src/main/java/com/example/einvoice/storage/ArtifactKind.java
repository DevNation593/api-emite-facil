package com.example.einvoice.storage;

import java.util.Arrays;
import java.util.Optional;

import org.springframework.http.MediaType;

/** Tipos de artefacto que se conservan de un comprobante. */
public enum ArtifactKind {

	XML_UNSIGNED("xml-unsigned"), XML_SIGNED("xml-signed"),

	/** Respuesta del SRI que autorizó el comprobante, tal como llegó. */
	SRI_AUTHORIZATION("sri-authorization"),

	/** XML autorizado que se entrega al receptor: datos de la autorización y el comprobante firmado. */
	XML_AUTHORIZED("xml-authorized"),

	/** Representación impresa del comprobante (RIDE), en PDF. */
	RIDE_PDF("ride", MediaType.APPLICATION_PDF);

	private final String pathName;

	private final MediaType mediaType;

	ArtifactKind(String pathName) {
		this(pathName, MediaType.APPLICATION_XML);
	}

	ArtifactKind(String pathName, MediaType mediaType) {
		this.pathName = pathName;
		this.mediaType = mediaType;
	}

	/** Tipo de contenido con el que se descarga. */
	public MediaType mediaType() {
		return mediaType;
	}

	/** Nombre con el que aparece en la ruta de descarga. */
	public String pathName() {
		return pathName;
	}

	public static Optional<ArtifactKind> fromPathName(String pathName) {
		return Arrays.stream(values()).filter(kind -> kind.pathName.equals(pathName)).findFirst();
	}

}
