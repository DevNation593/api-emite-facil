package com.example.einvoice.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.einvoice.support.TestCertificates;

class Pkcs12InspectorTest {

	static final char[] PASSWORD = TestCertificates.PASSWORD.toCharArray();

	static void assertRejected(byte[] p12, char[] password, String errorCode) {
		assertThatThrownBy(() -> Pkcs12Inspector.inspect(p12, password, Instant.now()))
			.isInstanceOfSatisfying(CertificateRejectedException.class,
					ex -> assertThat(ex.errorCode()).isEqualTo(errorCode));
	}

	@Test
	void readsValidCertificate() {
		SigningMaterial material = Pkcs12Inspector.inspect(TestCertificates.valid(), PASSWORD, Instant.now());
		assertThat(material.certificate().getSubjectX500Principal().getName()).contains("CN=Emisor de Pruebas firma");
		assertThat(((RSAPublicKey) material.certificate().getPublicKey()).getModulus())
			.isEqualTo(((RSAPrivateKey) material.privateKey()).getModulus());
	}

	@Test
	void acceptsSingleKeyWithoutKeyUsage() {
		assertThat(Pkcs12Inspector.inspect(TestCertificates.withoutKeyUsage(), PASSWORD, Instant.now())).isNotNull();
	}

	@Test
	void picksTheSigningKeyWhenThereIsAlsoAnEncryptionKey() {
		SigningMaterial material = Pkcs12Inspector.inspect(TestCertificates.signingAndEncryptionKeys(), PASSWORD,
				Instant.now());
		assertThat(material.certificate().getSubjectX500Principal().getName()).contains("CN=Emisor de Pruebas firma");
		assertThat(material.certificate().getKeyUsage()[0]).isTrue();
	}

	@Test
	void rejectsWrongPassword() {
		assertRejected(TestCertificates.valid(), "otra-clave".toCharArray(), "CERTIFICATE_UNREADABLE");
		assertRejected(TestCertificates.valid(), new char[0], "CERTIFICATE_UNREADABLE");
	}

	@Test
	void rejectsGarbage() {
		assertRejected("esto no es un p12".getBytes(StandardCharsets.UTF_8), PASSWORD, "CERTIFICATE_UNREADABLE");
		assertRejected(new byte[0], PASSWORD, "CERTIFICATE_UNREADABLE");
	}

	@Test
	void rejectsExpired() {
		assertRejected(TestCertificates.expired(), PASSWORD, "CERTIFICATE_EXPIRED");
	}

	@Test
	void rejectsNotYetValid() {
		assertRejected(TestCertificates.notYetValid(), PASSWORD, "CERTIFICATE_NOT_YET_VALID");
	}

	@Test
	void validityIsJudgedAtTheGivenInstant() {
		Instant inAYearAndAHalf = Instant.now().plusSeconds(550L * 24 * 3600);
		assertThatThrownBy(() -> Pkcs12Inspector.inspect(TestCertificates.valid(), PASSWORD, inAYearAndAHalf))
			.isInstanceOfSatisfying(CertificateRejectedException.class,
					ex -> assertThat(ex.errorCode()).isEqualTo("CERTIFICATE_EXPIRED"));
	}

	@Test
	void rejectsStoreWithoutPrivateKey() {
		assertRejected(TestCertificates.withoutPrivateKey(), PASSWORD, "CERTIFICATE_NO_PRIVATE_KEY");
	}

	@Test
	void rejectsTwoSigningKeys() {
		assertRejected(TestCertificates.twoSigningKeys(), PASSWORD, "CERTIFICATE_AMBIGUOUS");
	}

	@Test
	void rejectsKeyThatDoesNotMatchItsCertificate() {
		assertRejected(TestCertificates.mismatchedKey(), PASSWORD, "CERTIFICATE_KEY_MISMATCH");
	}

	@Test
	void rejectsShortKey() {
		assertRejected(TestCertificates.shortKey(), PASSWORD, "CERTIFICATE_KEY_UNSUPPORTED");
	}

}
