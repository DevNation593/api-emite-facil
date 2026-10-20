package com.example.einvoice.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;

import javax.xml.XMLConstants;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

/**
 * Allowlist cerrada para compilar los XSD sin acceso externo (docs/14-xsd-integration.md): solo
 * resuelve el import de XMLDSig, con la única copia compartida de {@code xsd/common/}, y el DTD que ese
 * archivo declara. Los XSD del SRI se conservan sin editar: importan {@code xmldsig-core-schema.xsd} con
 * ruta relativa y es este resolver el que lo encuentra, cualquiera sea el tipo o la versión.
 */
public final class ClasspathSchemaResolver implements LSResourceResolver {

	private static final String DTD_TYPE = "http://www.w3.org/TR/REC-xml";

	private static final String XMLDSIG_SCHEMA = "xmldsig-core-schema.xsd";

	static final String XMLDSIG_RESOURCE = "xsd/common/" + XMLDSIG_SCHEMA;

	private static final String XML_SCHEMA_DTD_PUBLIC_ID = "-//W3C//DTD XMLSchema 200102//EN";

	@Override
	public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId,
			String baseURI) {
		if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(type) && XMLDSIG_SCHEMA.equals(systemId)) {
			return packaged(XMLDSIG_RESOURCE);
		}
		if (DTD_TYPE.equals(type) && XML_SCHEMA_DTD_PUBLIC_ID.equals(publicId)) {
			// El subconjunto interno de xmldsig-core-schema.xsd ya declara lo que necesita.
			return new Input(new byte[0], systemId, publicId);
		}
		throw new IllegalArgumentException(
				"Recurso de esquema no permitido: type=" + type + " publicId=" + publicId + " systemId=" + systemId);
	}

	private static LSInput packaged(String resource) {
		URL url = ClasspathSchemaResolver.class.getClassLoader().getResource(resource);
		if (url == null) {
			throw new IllegalArgumentException("Recurso de esquema no empacado: " + resource);
		}
		try (InputStream in = url.openStream()) {
			return new Input(in.readAllBytes(), url.toExternalForm(), null);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private record Input(byte[] bytes, String systemId, String publicId) implements LSInput {

		@Override
		public InputStream getByteStream() {
			return new ByteArrayInputStream(bytes);
		}

		@Override
		public String getSystemId() {
			return systemId;
		}

		@Override
		public String getPublicId() {
			return publicId;
		}

		@Override
		public Reader getCharacterStream() {
			return null;
		}

		@Override
		public String getStringData() {
			return null;
		}

		@Override
		public String getBaseURI() {
			return null;
		}

		@Override
		public String getEncoding() {
			return null;
		}

		@Override
		public boolean getCertifiedText() {
			return false;
		}

		@Override
		public void setByteStream(InputStream byteStream) {
		}

		@Override
		public void setSystemId(String systemId) {
		}

		@Override
		public void setPublicId(String publicId) {
		}

		@Override
		public void setCharacterStream(Reader characterStream) {
		}

		@Override
		public void setStringData(String stringData) {
		}

		@Override
		public void setBaseURI(String baseURI) {
		}

		@Override
		public void setEncoding(String encoding) {
		}

		@Override
		public void setCertifiedText(boolean certifiedText) {
		}

	}

}
