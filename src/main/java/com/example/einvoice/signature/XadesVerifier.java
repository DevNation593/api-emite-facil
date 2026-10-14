package com.example.einvoice.signature;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Comprueba que un comprobante firmado cumple el perfil de la ficha técnica y que su firma es
 * criptográficamente válida con el certificado que lleva dentro. Se usa antes de dar un XML por
 * firmado. No valida la cadena de confianza ni la revocación del certificado: eso lo hace el SRI.
 */
@Component
public class XadesVerifier {

	public SignatureVerification verify(byte[] signedXml) {
		List<String> errors = new ArrayList<>();
		try {
			check(XadesProfile.parse(signedXml), errors);
		}
		catch (SAXException | IOException ex) {
			errors.add("El comprobante no es un XML legible");
		}
		catch (GeneralSecurityException | MarshalException | XMLSignatureException | RuntimeException ex) {
			errors.add("La firma no se pudo evaluar: " + ex.getClass().getSimpleName());
		}
		return SignatureVerification.of(errors);
	}

	private static void check(Document document, List<String> errors)
			throws GeneralSecurityException, MarshalException, XMLSignatureException {
		Element root = document.getDocumentElement();
		NodeList signatures = document.getElementsByTagNameNS(XadesProfile.DS, "Signature");
		if (signatures.getLength() != 1 || signatures.item(0).getParentNode() != root) {
			errors.add("Debe haber exactamente una firma, hija del elemento raíz");
			return;
		}
		Element signatureElement = (Element) signatures.item(0);
		Element signedProperties = single(signatureElement, XadesProfile.XADES, "SignedProperties");
		Element keyInfoElement = single(signatureElement, XadesProfile.DS, "KeyInfo");
		Element certificateElement = single(signatureElement, XadesProfile.DS, "X509Certificate");
		if (signedProperties == null || keyInfoElement == null || certificateElement == null
				|| !XadesProfile.DOCUMENT_ID.equals(root.getAttribute("id"))) {
			errors.add("La firma no tiene la estructura XAdES-BES esperada");
			return;
		}
		X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
			.generateCertificate(new ByteArrayInputStream(
					Base64.getMimeDecoder().decode(certificateElement.getTextContent())));

		DOMValidateContext context = new DOMValidateContext(certificate.getPublicKey(), signatureElement);
		// El SRI exige RSA-SHA1, que la validación segura del JDK rechaza por defecto. A cambio, abajo
		// se comprueba que la firma solo usa las referencias, transformaciones y algoritmos del perfil.
		context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
		context.setIdAttributeNS(root, null, "id");
		context.setIdAttributeNS(signedProperties, null, "Id");
		XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);

		checkProfile(signature, signedProperties.getAttribute("Id"), keyInfoElement.getAttribute("Id"), errors);
		checkSigningCertificate(signedProperties, certificate, errors);
		if (!errors.isEmpty()) {
			return;
		}
		if (!signature.validate(context)) {
			if (!signature.getSignatureValue().validate(context)) {
				errors.add("El valor de la firma no corresponde al certificado");
			}
			for (Reference reference : signature.getSignedInfo().getReferences()) {
				if (!reference.validate(context)) {
					errors.add("El contenido referido por " + reference.getURI() + " fue alterado");
				}
			}
			if (errors.isEmpty()) {
				errors.add("La firma no es válida");
			}
		}
	}

	/** Exactamente las tres referencias del Anexo 14, con sus algoritmos y sin transformaciones de más. */
	private static void checkProfile(XMLSignature signature, String signedPropertiesId, String keyInfoId,
			List<String> errors) {
		if (!XadesProfile.C14N.equals(signature.getSignedInfo().getCanonicalizationMethod().getAlgorithm())
				|| !XadesProfile.RSA_SHA1.equals(signature.getSignedInfo().getSignatureMethod().getAlgorithm())) {
			errors.add("La firma no usa los algoritmos del perfil del SRI");
		}
		List<Reference> references = signature.getSignedInfo().getReferences();
		boolean properties = false;
		boolean keyInfo = false;
		boolean document = false;
		for (Reference reference : references) {
			List<Transform> transforms = reference.getTransforms();
			if (!XadesProfile.SHA1.equals(reference.getDigestMethod().getAlgorithm())) {
				errors.add("Una referencia no usa el digest del perfil del SRI");
			}
			if (("#" + signedPropertiesId).equals(reference.getURI())
					&& XadesProfile.SIGNED_PROPERTIES_TYPE.equals(reference.getType()) && transforms.isEmpty()) {
				properties = true;
			}
			else if (("#" + keyInfoId).equals(reference.getURI()) && !keyInfoId.isEmpty() && transforms.isEmpty()) {
				keyInfo = true;
			}
			else if (("#" + XadesProfile.DOCUMENT_ID).equals(reference.getURI()) && transforms.size() == 1
					&& XadesProfile.ENVELOPED.equals(transforms.get(0).getAlgorithm())) {
				document = true;
			}
			else {
				errors.add("La firma contiene una referencia que el perfil no admite");
			}
		}
		if (references.size() != 3 || !properties || !keyInfo || !document) {
			errors.add("La firma debe cubrir el comprobante, SignedProperties y KeyInfo");
		}
	}

	/** El certificado de KeyInfo debe ser el que declara SigningCertificate. */
	private static void checkSigningCertificate(Element signedProperties, X509Certificate certificate,
			List<String> errors) throws GeneralSecurityException {
		Element certDigest = single(signedProperties, XadesProfile.XADES, "CertDigest");
		Element digestValue = certDigest == null ? null : single(certDigest, XadesProfile.DS, "DigestValue");
		Element issuerName = single(signedProperties, XadesProfile.DS, "X509IssuerName");
		Element serialNumber = single(signedProperties, XadesProfile.DS, "X509SerialNumber");
		if (digestValue == null || issuerName == null || serialNumber == null) {
			errors.add("SigningCertificate está incompleto");
			return;
		}
		byte[] declared = Base64.getMimeDecoder().decode(digestValue.getTextContent());
		byte[] actual = MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded());
		if (!Arrays.equals(declared, actual)
				|| !certificate.getIssuerX500Principal().getName().equals(issuerName.getTextContent())
				|| !certificate.getSerialNumber().toString().equals(serialNumber.getTextContent().strip())) {
			errors.add("El certificado de KeyInfo no es el declarado en SigningCertificate");
		}
	}

	/** El único descendiente con ese nombre, o nulo si no hay exactamente uno. */
	private static Element single(Element parent, String namespace, String localName) {
		NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
		Node node = nodes.getLength() == 1 ? nodes.item(0) : null;
		return (Element) node;
	}

}
