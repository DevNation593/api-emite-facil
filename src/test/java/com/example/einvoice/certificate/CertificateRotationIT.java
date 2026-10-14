package com.example.einvoice.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.orchestration.DocumentProcessor;
import com.example.einvoice.signature.XadesVerifier;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestCertificates;

/**
 * Renovar el certificado de un emisor: lo nuevo se firma con el nuevo, y lo ya firmado con el
 * anterior sigue siendo verificable y nadie lo vuelve a firmar.
 */
class CertificateRotationIT extends AbstractIntegrationTest {

	static final Pattern CERTIFICATE = Pattern.compile("X509Certificate>([^<]+)<");

	@Autowired
	DocumentProcessor processor;

	@Autowired
	ArtifactStore artifacts;

	final XadesVerifier verifier = new XadesVerifier();

	byte[] signedXml(BillingScenario s, UUID document) {
		return artifacts.find(s.tenant(), document, ArtifactKind.XML_SIGNED).orElseThrow().content();
	}

	/** Huella SHA-256 del certificado con el que se firmó el XML. */
	static String signerFingerprint(byte[] signedXml) {
		Matcher matcher = CERTIFICATE.matcher(new String(signedXml, StandardCharsets.UTF_8));
		assertThat(matcher.find()).isTrue();
		return ArtifactStore.sha256(Base64.getMimeDecoder().decode(matcher.group(1)));
	}

	String fingerprintWithStatus(BillingScenario s, String status) {
		return jdbc.queryForObject("SELECT fingerprint_sha256 FROM certificates WHERE taxpayer_id = ? AND status = ?",
				String.class, s.taxpayer(), status);
	}

	@Test
	void aRenewedCertificateSignsWhatComesNextAndLeavesTheRestAlone() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		UUID before = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		assertThat(processor.generateAndSign(s.tenant(), before)).isEqualTo("SIGNED");
		byte[] signedBefore = signedXml(s, before);

		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.anotherValid());
		UUID after = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		assertThat(processor.generateAndSign(s.tenant(), after)).isEqualTo("SIGNED");

		String previous = fingerprintWithStatus(s, "REPLACED");
		String current = fingerprintWithStatus(s, "ACTIVE");
		assertThat(previous).isNotEqualToIgnoringCase(current);
		assertThat(signerFingerprint(signedXml(s, after))).isEqualToIgnoringCase(current);

		// Lo firmado antes de la renovación no cambia ni se vuelve a firmar, y sigue verificando.
		assertThat(processor.generateAndSign(s.tenant(), before)).isEqualTo("SIGNED");
		assertThat(signedXml(s, before)).isEqualTo(signedBefore);
		assertThat(signerFingerprint(signedBefore)).isEqualToIgnoringCase(previous);
		assertThat(verifier.verify(signedBefore).errors()).isEmpty();
		assertThat(verifier.verify(signedXml(s, after)).errors()).isEmpty();
	}

}
