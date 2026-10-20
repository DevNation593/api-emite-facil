package com.example.einvoice.signature;

import static com.example.einvoice.xml.InvoiceXmlFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.example.einvoice.certificate.Pkcs12Inspector;
import com.example.einvoice.certificate.SigningMaterial;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.xml.DocumentSchemas;
import com.example.einvoice.xml.InvoiceXmlBuilder;
import com.example.einvoice.xml.InvoiceXmlData;
import com.example.einvoice.xml.InvoiceXmlFixtures;
import com.example.einvoice.xml.XsdValidator;

/** Firma XAdES-BES según la ficha técnica v2.34: sección 6 y ejemplo del Anexo 14. */
class XadesSignatureIT {

	static final String DS = "http://www.w3.org/2000/09/xmldsig#";

	static final String ETSI = "http://uri.etsi.org/01903/v1.3.2#";

	static final String SHA1 = "http://www.w3.org/2000/09/xmldsig#sha1";

	static final Instant SIGNING_TIME = Instant.parse("2026-10-07T21:57:32Z");

	static final SigningMaterial MATERIAL = Pkcs12Inspector.inspect(TestCertificates.valid(),
			TestCertificates.PASSWORD.toCharArray(), Instant.now());

	static final SigningMaterial OTHER = Pkcs12Inspector.inspect(TestCertificates.anotherValid(),
			TestCertificates.PASSWORD.toCharArray(), Instant.now());

	final XmlSigner signer = new XadesBesSigner();

	final XadesVerifier verifier = new XadesVerifier();

	static byte[] unsigned() {
		return InvoiceXmlBuilder.build(InvoiceXmlFixtures.standard());
	}

	byte[] signed() {
		return signer.sign(unsigned(), MATERIAL, SIGNING_TIME);
	}

	static Document parse(byte[] xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
	}

	static Element only(Document document, String namespace, String localName) {
		NodeList nodes = document.getElementsByTagNameNS(namespace, localName);
		assertThat(nodes.getLength()).as(localName).isEqualTo(1);
		return (Element) nodes.item(0);
	}

	static String text(byte[] xml) {
		return new String(xml, StandardCharsets.UTF_8);
	}

	@Test
	void signatureHasTheStructureOfAnnex14() throws Exception {
		Document document = parse(signed());
		Element root = document.getDocumentElement();
		Element signature = only(document, DS, "Signature");
		assertThat(signature.getParentNode()).isSameAs(root);
		assertThat(root.getLastChild()).isSameAs(signature);
		assertThat(signature.getPrefix()).isEqualTo("ds");
		String signatureId = signature.getAttribute("Id");
		assertThat(signatureId).isNotBlank();

		assertThat(only(document, DS, "CanonicalizationMethod").getAttribute("Algorithm"))
			.isEqualTo("http://www.w3.org/TR/2001/REC-xml-c14n-20010315");
		assertThat(only(document, DS, "SignatureMethod").getAttribute("Algorithm"))
			.isEqualTo("http://www.w3.org/2000/09/xmldsig#rsa-sha1");
		assertThat(only(document, DS, "SignedInfo").getAttribute("Id")).isNotBlank();
		assertThat(only(document, DS, "SignatureValue").getAttribute("Id")).isNotBlank();

		Element keyInfo = only(document, DS, "KeyInfo");
		Element signedProperties = only(document, ETSI, "SignedProperties");
		assertThat(signedProperties.getPrefix()).isEqualTo("etsi");
		NodeList references = only(document, DS, "SignedInfo").getElementsByTagNameNS(DS, "Reference");
		assertThat(references.getLength()).isEqualTo(3);

		Element toProperties = (Element) references.item(0);
		assertThat(toProperties.getAttribute("Type")).isEqualTo("http://uri.etsi.org/01903#SignedProperties");
		assertThat(toProperties.getAttribute("URI")).isEqualTo("#" + signedProperties.getAttribute("Id"));
		assertThat(toProperties.getAttribute("Id")).isNotBlank();
		Element toKeyInfo = (Element) references.item(1);
		assertThat(toKeyInfo.getAttribute("URI")).isEqualTo("#" + keyInfo.getAttribute("Id"));
		Element toDocument = (Element) references.item(2);
		assertThat(toDocument.getAttribute("URI")).isEqualTo("#comprobante");
		NodeList transforms = toDocument.getElementsByTagNameNS(DS, "Transform");
		assertThat(transforms.getLength()).isEqualTo(1);
		assertThat(((Element) transforms.item(0)).getAttribute("Algorithm"))
			.isEqualTo("http://www.w3.org/2000/09/xmldsig#enveloped-signature");
		assertThat(toProperties.getElementsByTagNameNS(DS, "Transform").getLength()).isZero();
		assertThat(toKeyInfo.getElementsByTagNameNS(DS, "Transform").getLength()).isZero();
		NodeList digestMethods = document.getElementsByTagNameNS(DS, "DigestMethod");
		assertThat(digestMethods.getLength()).isEqualTo(4);
		for (int i = 0; i < digestMethods.getLength(); i++) {
			assertThat(((Element) digestMethods.item(i)).getAttribute("Algorithm")).isEqualTo(SHA1);
		}

		String certificateBase64 = only(document, DS, "X509Certificate").getTextContent().replaceAll("\\s", "");
		assertThat(Base64.getDecoder().decode(certificateBase64)).isEqualTo(MATERIAL.certificate().getEncoded());
		assertThat(keyInfo.getElementsByTagNameNS(DS, "RSAKeyValue").getLength()).isEqualTo(1);
		assertThat(only(document, DS, "Modulus").getTextContent()).isNotBlank();
		assertThat(only(document, DS, "Exponent").getTextContent()).isEqualTo("AQAB");

		Element qualifying = only(document, ETSI, "QualifyingProperties");
		assertThat(qualifying.getAttribute("Target")).isEqualTo("#" + signatureId);
		assertThat(qualifying.getParentNode().getLocalName()).isEqualTo("Object");
		assertThat(only(document, ETSI, "SigningTime").getTextContent()).isEqualTo("2026-10-07T16:57:32-05:00");
		String certDigest = only(document, ETSI, "CertDigest").getElementsByTagNameNS(DS, "DigestValue")
			.item(0)
			.getTextContent();
		assertThat(Base64.getDecoder().decode(certDigest))
			.isEqualTo(MessageDigest.getInstance("SHA-1").digest(MATERIAL.certificate().getEncoded()));
		assertThat(only(document, DS, "X509IssuerName").getTextContent())
			.isEqualTo(MATERIAL.certificate().getIssuerX500Principal().getName());
		assertThat(only(document, DS, "X509SerialNumber").getTextContent())
			.isEqualTo(MATERIAL.certificate().getSerialNumber().toString());
		Element format = only(document, ETSI, "DataObjectFormat");
		assertThat(format.getAttribute("ObjectReference")).isEqualTo("#" + toDocument.getAttribute("Id"));
		assertThat(only(document, ETSI, "Description").getTextContent()).isEqualTo("contenido comprobante");
		assertThat(only(document, ETSI, "MimeType").getTextContent()).isEqualTo("text/xml");
	}

	@Test
	void signedInvoiceVerifies() {
		SignatureVerification verification = verifier.verify(signed());
		assertThat(verification.errors()).isEmpty();
		assertThat(verification.valid()).isTrue();
	}

	@Test
	void signedInvoiceIsStillValidAgainstXsd() {
		assertThat(new XsdValidator(new DocumentSchemas()).validate(signed(), InvoiceXmlBuilder.VERSION).errors())
			.isEmpty();
	}

	@Test
	void signingDoesNotChangeTheInvoiceContent() throws Exception {
		Document signedDocument = parse(signed());
		Element signature = only(signedDocument, DS, "Signature");
		signature.getParentNode().removeChild(signature);
		signedDocument.normalizeDocument();
		assertThat(signedDocument.getDocumentElement().isEqualNode(parse(unsigned()).getDocumentElement())).isTrue();
		assertThat(text(signed())).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"");
	}

	@Test
	void tamperedAmountFailsVerification() {
		String tampered = text(signed()).replace("<importeTotal>37.00</importeTotal>",
				"<importeTotal>3.70</importeTotal>");
		assertThat(tampered).contains("<importeTotal>3.70</importeTotal>");
		assertThat(verifier.verify(tampered.getBytes(StandardCharsets.UTF_8)).valid()).isFalse();
	}

	@Test
	void tamperedSignedPropertiesFailsVerification() {
		String tampered = text(signed()).replace("2026-10-07T16:57:32-05:00", "2026-10-08T16:57:32-05:00");
		assertThat(tampered).contains("2026-10-08T16:57:32-05:00");
		assertThat(verifier.verify(tampered.getBytes(StandardCharsets.UTF_8)).valid()).isFalse();
	}

	@Test
	void swappedCertificateFailsVerification() throws Exception {
		byte[] signed = signed();
		String original = only(parse(signed), DS, "X509Certificate").getTextContent();
		String other = Base64.getMimeEncoder(76, new byte[] { '\n' }).encodeToString(OTHER.certificate().getEncoded());
		String tampered = text(signed).replace(original, other);
		assertThat(tampered).isNotEqualTo(text(signed));
		assertThat(verifier.verify(tampered.getBytes(StandardCharsets.UTF_8)).valid()).isFalse();
	}

	@Test
	void signatureByAnotherKeyFailsVerification() {
		SigningMaterial mismatched = new SigningMaterial(OTHER.privateKey(), MATERIAL.certificate());
		assertThat(verifier.verify(signer.sign(unsigned(), mismatched, SIGNING_TIME)).valid()).isFalse();
	}

	@Test
	void specialCharactersSurviveSigning() throws Exception {
		String name = "Ñandú & Hijos <\"S.A.\"> 'O'Brien' ✅ 😀";
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.legalName = name;
		byte[] signed = signer.sign(InvoiceXmlBuilder.build(builder.build()), MATERIAL, SIGNING_TIME);

		assertThat(verifier.verify(signed).errors()).isEmpty();
		assertThat(parse(signed).getElementsByTagName("razonSocial").item(0).getTextContent()).isEqualTo(name);
	}

	@Test
	void refusesToSignTwice() {
		byte[] signed = signed();
		assertThatThrownBy(() -> signer.sign(signed, MATERIAL, SIGNING_TIME))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void refusesDocumentWithoutComprobanteId() {
		byte[] withoutId = text(unsigned()).replace(" id=\"comprobante\"", "").getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> signer.sign(withoutId, MATERIAL, SIGNING_TIME))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> signer.sign("<factura".getBytes(StandardCharsets.UTF_8), MATERIAL, SIGNING_TIME))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unsignedOrUnreadableDocumentDoesNotVerify() {
		SignatureVerification unsigned = verifier.verify(unsigned());
		assertThat(unsigned.valid()).isFalse();
		assertThat(unsigned.errors()).isNotEmpty();
		assertThat(verifier.verify("<factura".getBytes(StandardCharsets.UTF_8)).valid()).isFalse();
	}

	@Test
	void signatureMovedOutOfTheRootDoesNotVerify() {
		String signed = text(signed());
		int start = signed.indexOf("<ds:Signature");
		int end = signed.indexOf("</ds:Signature>") + "</ds:Signature>".length();
		String signature = signed.substring(start, end);
		String moved = signed.substring(0, start).replace("</infoFactura>", signature + "</infoFactura>")
				+ signed.substring(end);
		assertThat(verifier.verify(moved.getBytes(StandardCharsets.UTF_8)).valid()).isFalse();
	}

	@Test
	void largeInvoiceSignsAndVerifies() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		InvoiceXmlData.Line line = new InvoiceXmlData.Line("SRV-001", "Servicio de consultoría", d("2"), d("10.00"),
				d("0.00"), d("20.00"), "2", "4", d("15.00"), d("3.00"));
		builder.lines = new ArrayList<>(List.of());
		for (int i = 0; i < 500; i++) {
			builder.lines.add(line);
		}
		byte[] signed = signer.sign(InvoiceXmlBuilder.build(builder.build()), MATERIAL, SIGNING_TIME);
		assertThat(verifier.verify(signed).valid()).isTrue();
	}

}
