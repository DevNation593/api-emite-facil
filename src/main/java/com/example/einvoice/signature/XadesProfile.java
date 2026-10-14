package com.example.einvoice.signature;

import java.io.IOException;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.example.einvoice.xml.SecureXml;

/**
 * Parámetros de firma que fija la ficha técnica v2.34 del SRI (sección 6 y Anexo 14). SHA-1 es
 * criptográficamente débil, pero es lo que el SRI exige: no se cambia por decisión propia.
 */
final class XadesProfile {

	static final String DS = "http://www.w3.org/2000/09/xmldsig#";

	static final String XADES = "http://uri.etsi.org/01903/v1.3.2#";

	static final String C14N = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315";

	static final String RSA_SHA1 = "http://www.w3.org/2000/09/xmldsig#rsa-sha1";

	static final String SHA1 = "http://www.w3.org/2000/09/xmldsig#sha1";

	static final String ENVELOPED = "http://www.w3.org/2000/09/xmldsig#enveloped-signature";

	static final String SIGNED_PROPERTIES_TYPE = "http://uri.etsi.org/01903#SignedProperties";

	/** Valor del atributo id de la raíz de todo comprobante, y destino de la referencia al documento. */
	static final String DOCUMENT_ID = "comprobante";

	private XadesProfile() {
	}

	/** Lee un XML con espacios de nombres, sin DOCTYPE y sin acceso externo. */
	static Document parse(byte[] xml) throws SAXException, IOException {
		return SecureXml.parse(xml);
	}

}
