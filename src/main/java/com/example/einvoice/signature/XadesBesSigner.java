package com.example.einvoice.signature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLObject;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.example.einvoice.certificate.SigningMaterial;
import com.example.einvoice.sequence.FiscalClock;

/**
 * Firma XAdES-BES con la estructura del Anexo 14 de la ficha técnica del SRI: la firma cubre el
 * comprobante, el elemento SignedProperties y el KeyInfo que lleva el certificado. Se construye con
 * la API XMLDSig del JDK; los elementos de XAdES y el KeyInfo se escriben aquí para controlar su forma.
 */
@Component
public class XadesBesSigner implements XmlSigner {

	private static final DateTimeFormatter SIGNING_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	private final SecureRandom random = new SecureRandom();

	@Override
	public byte[] sign(byte[] unsignedXml, SigningMaterial material, Instant signingTime) {
		Document document;
		try {
			document = XadesProfile.parse(unsignedXml);
		}
		catch (SAXException | IOException ex) {
			throw new IllegalArgumentException("El comprobante no es un XML legible", ex);
		}
		Element root = document.getDocumentElement();
		if (!XadesProfile.DOCUMENT_ID.equals(root.getAttribute("id"))) {
			throw new IllegalArgumentException("La raíz del comprobante debe tener id=\"comprobante\"");
		}
		if (document.getElementsByTagNameNS(XadesProfile.DS, "Signature").getLength() > 0) {
			throw new IllegalArgumentException("El comprobante ya está firmado");
		}
		try {
			signInto(document, root, material, signingTime);
			return serialize(document);
		}
		catch (GeneralSecurityException | MarshalException | XMLSignatureException | TransformerException ex) {
			throw new IllegalStateException("No se pudo firmar el comprobante", ex);
		}
	}

	private void signInto(Document document, Element root, SigningMaterial material, Instant signingTime)
			throws GeneralSecurityException, MarshalException, XMLSignatureException {
		X509Certificate certificate = material.certificate();
		String signatureId = "Signature" + number();
		String signedPropertiesId = signatureId + "-SignedProperties" + number();
		String keyInfoId = "Certificate" + number();
		String documentReferenceId = "Reference-ID-" + number();

		Element qualifyingProperties = qualifyingProperties(document, certificate, signingTime, signatureId,
				signedPropertiesId, documentReferenceId);
		Element signedProperties = (Element) qualifyingProperties.getFirstChild();

		XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
		DigestMethod sha1 = factory.newDigestMethod(XadesProfile.SHA1, null);
		Reference toSignedProperties = factory.newReference("#" + signedPropertiesId, sha1, null,
				XadesProfile.SIGNED_PROPERTIES_TYPE, "SignedPropertiesID" + number());
		Reference toKeyInfo = factory.newReference("#" + keyInfoId, sha1);
		Reference toDocument = factory.newReference("#" + XadesProfile.DOCUMENT_ID, sha1,
				List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)), null,
				documentReferenceId);
		SignedInfo signedInfo = factory.newSignedInfo(
				factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
				factory.newSignatureMethod(XadesProfile.RSA_SHA1, null),
				List.of(toSignedProperties, toKeyInfo, toDocument), "Signature-SignedInfo" + number());
		KeyInfo keyInfo = factory.getKeyInfoFactory()
			.newKeyInfo(List.of(new DOMStructure(x509Data(document, certificate)),
					new DOMStructure(keyValue(document, certificate))), keyInfoId);
		XMLObject object = factory.newXMLObject(List.of(new DOMStructure(qualifyingProperties)),
				signatureId + "-Object" + number(), null, null);
		XMLSignature signature = factory.newXMLSignature(signedInfo, keyInfo, List.of(object), signatureId,
				"SignatureValue" + number());

		DOMSignContext context = new DOMSignContext(material.privateKey(), root);
		context.setDefaultNamespacePrefix("ds");
		context.putNamespacePrefix(XadesProfile.DS, "ds");
		context.putNamespacePrefix(XadesProfile.XADES, "etsi");
		context.setIdAttributeNS(root, null, "id");
		context.setIdAttributeNS(signedProperties, null, "Id");
		signature.sign(context);

		// SignatureValue no está cubierto por la firma: se deja en una sola línea, sin saltos que al
		// serializar se convertirían en referencias de carácter.
		NodeList values = document.getElementsByTagNameNS(XadesProfile.DS, "SignatureValue");
		Element value = (Element) values.item(0);
		value.setTextContent(value.getTextContent().replaceAll("\\s", ""));
	}

	/** etsi:QualifyingProperties con las propiedades firmadas que pide XAdES-BES. */
	private static Element qualifyingProperties(Document document, X509Certificate certificate, Instant signingTime,
			String signatureId, String signedPropertiesId, String documentReferenceId)
			throws GeneralSecurityException {
		Element qualifying = etsi(document, "QualifyingProperties");
		// La declaración va en el propio elemento para que la canonicalización al firmar y al
		// verificar vean los mismos espacios de nombres.
		qualifying.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:etsi", XadesProfile.XADES);
		qualifying.setAttribute("Target", "#" + signatureId);

		Element signedProperties = child(qualifying, etsi(document, "SignedProperties"));
		signedProperties.setAttribute("Id", signedPropertiesId);
		signedProperties.setIdAttribute("Id", true);

		Element signatureProperties = child(signedProperties, etsi(document, "SignedSignatureProperties"));
		child(signatureProperties, etsi(document, "SigningTime")).setTextContent(
				OffsetDateTime.ofInstant(signingTime, FiscalClock.ZONE).truncatedTo(ChronoUnit.SECONDS).format(SIGNING_TIME));
		Element cert = child(child(signatureProperties, etsi(document, "SigningCertificate")), etsi(document, "Cert"));
		Element certDigest = child(cert, etsi(document, "CertDigest"));
		child(certDigest, ds(document, "DigestMethod")).setAttribute("Algorithm", XadesProfile.SHA1);
		child(certDigest, ds(document, "DigestValue")).setTextContent(
				base64(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded())));
		Element issuerSerial = child(cert, etsi(document, "IssuerSerial"));
		child(issuerSerial, ds(document, "X509IssuerName"))
			.setTextContent(certificate.getIssuerX500Principal().getName());
		child(issuerSerial, ds(document, "X509SerialNumber")).setTextContent(certificate.getSerialNumber().toString());

		Element dataObjectFormat = child(child(signedProperties, etsi(document, "SignedDataObjectProperties")),
				etsi(document, "DataObjectFormat"));
		dataObjectFormat.setAttribute("ObjectReference", "#" + documentReferenceId);
		child(dataObjectFormat, etsi(document, "Description")).setTextContent("contenido comprobante");
		child(dataObjectFormat, etsi(document, "MimeType")).setTextContent("text/xml");
		return qualifying;
	}

	private static Element x509Data(Document document, X509Certificate certificate) throws GeneralSecurityException {
		Element data = ds(document, "X509Data");
		child(data, ds(document, "X509Certificate")).setTextContent(base64(certificate.getEncoded()));
		return data;
	}

	private static Element keyValue(Document document, X509Certificate certificate) {
		RSAPublicKey key = (RSAPublicKey) certificate.getPublicKey();
		Element keyValue = ds(document, "KeyValue");
		Element rsa = child(keyValue, ds(document, "RSAKeyValue"));
		child(rsa, ds(document, "Modulus")).setTextContent(base64(unsigned(key.getModulus())));
		child(rsa, ds(document, "Exponent")).setTextContent(base64(unsigned(key.getPublicExponent())));
		return keyValue;
	}

	private static byte[] serialize(Document document) throws TransformerException {
		document.setXmlStandalone(true);
		TransformerFactory factory = TransformerFactory.newDefaultInstance();
		factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
		Transformer transformer = factory.newTransformer();
		transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		transformer.setOutputProperty(OutputKeys.INDENT, "no");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		transformer.transform(new DOMSource(document), new StreamResult(out));
		return out.toByteArray();
	}

	private static Element etsi(Document document, String name) {
		return document.createElementNS(XadesProfile.XADES, "etsi:" + name);
	}

	private static Element ds(Document document, String name) {
		return document.createElementNS(XadesProfile.DS, "ds:" + name);
	}

	private static Element child(Element parent, Element child) {
		parent.appendChild(child);
		return child;
	}

	private static String base64(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	/** Magnitud sin el byte de signo que añade BigInteger, como pide el tipo CryptoBinary de XMLDSig. */
	private static byte[] unsigned(BigInteger value) {
		byte[] bytes = value.toByteArray();
		if (bytes.length > 1 && bytes[0] == 0) {
			byte[] trimmed = new byte[bytes.length - 1];
			System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
			return trimmed;
		}
		return bytes;
	}

	private int number() {
		return 100_000 + random.nextInt(900_000);
	}

}
