package com.example.einvoice.certificate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lee un archivo PKCS#12 y elige la clave de firma. La ficha técnica del SRI (6.8) pide RSA de 2048
 * bits. Los certificados ecuatorianos suelen traer dos claves, una de firma y otra de cifrado: se
 * usa la que declara el uso digitalSignature.
 */
public final class Pkcs12Inspector {

	private static final int MIN_RSA_BITS = 2048;

	/** Posición de digitalSignature en la extensión KeyUsage. */
	private static final int DIGITAL_SIGNATURE = 0;

	private Pkcs12Inspector() {
	}

	public static SigningMaterial inspect(byte[] p12, char[] password, Instant now) {
		KeyStore store = load(p12, password);
		List<SigningMaterial> withKey = new ArrayList<>();
		try {
			for (String alias : Collections.list(store.aliases())) {
				if (!store.isKeyEntry(alias)) {
					continue;
				}
				Key key = store.getKey(alias, password);
				Certificate certificate = store.getCertificate(alias);
				if (key instanceof PrivateKey privateKey && certificate instanceof X509Certificate x509) {
					withKey.add(new SigningMaterial(privateKey, x509));
				}
			}
		}
		catch (GeneralSecurityException ex) {
			throw new CertificateRejectedException("CERTIFICATE_UNREADABLE", "No se pudo leer la clave del certificado");
		}
		if (withKey.isEmpty()) {
			throw new CertificateRejectedException("CERTIFICATE_NO_PRIVATE_KEY",
					"El archivo no contiene un certificado con clave privada");
		}
		SigningMaterial chosen = choose(withKey);
		X509Certificate certificate = chosen.certificate();
		if (!(certificate.getPublicKey() instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < MIN_RSA_BITS) {
			throw new CertificateRejectedException("CERTIFICATE_KEY_UNSUPPORTED",
					"La clave del certificado debe ser RSA de al menos 2048 bits");
		}
		if (!matches(chosen)) {
			throw new CertificateRejectedException("CERTIFICATE_KEY_MISMATCH",
					"La clave privada del archivo no corresponde a su certificado");
		}
		if (now.isBefore(certificate.getNotBefore().toInstant())) {
			throw new CertificateRejectedException("CERTIFICATE_NOT_YET_VALID", "El certificado aún no está vigente");
		}
		if (now.isAfter(certificate.getNotAfter().toInstant())) {
			throw new CertificateRejectedException("CERTIFICATE_EXPIRED", "El certificado está caducado");
		}
		return chosen;
	}

	private static KeyStore load(byte[] p12, char[] password) {
		try {
			KeyStore store = KeyStore.getInstance("PKCS12");
			store.load(new ByteArrayInputStream(p12), password);
			return store;
		}
		catch (IOException | GeneralSecurityException ex) {
			// Contraseña errónea y archivo corrupto no se distinguen: el mensaje no da pistas de cuál fue.
			throw new CertificateRejectedException("CERTIFICATE_UNREADABLE",
					"El archivo no es un PKCS#12 válido o la contraseña es incorrecta");
		}
	}

	private static SigningMaterial choose(List<SigningMaterial> withKey) {
		List<SigningMaterial> signing = withKey.stream().filter(Pkcs12Inspector::declaresDigitalSignature).toList();
		if (signing.size() == 1) {
			return signing.get(0);
		}
		if (signing.isEmpty() && withKey.size() == 1) {
			return withKey.get(0);
		}
		throw new CertificateRejectedException("CERTIFICATE_AMBIGUOUS",
				"El archivo contiene más de una clave que podría ser la de firma");
	}

	/** Firma unos bytes con la clave privada y los verifica con el certificado. */
	private static boolean matches(SigningMaterial material) {
		try {
			byte[] probe = new byte[32];
			new SecureRandom().nextBytes(probe);
			Signature signer = Signature.getInstance("SHA256withRSA");
			signer.initSign(material.privateKey());
			signer.update(probe);
			byte[] signature = signer.sign();
			Signature verifier = Signature.getInstance("SHA256withRSA");
			verifier.initVerify(material.certificate().getPublicKey());
			verifier.update(probe);
			return verifier.verify(signature);
		}
		catch (GeneralSecurityException ex) {
			return false;
		}
	}

	private static boolean declaresDigitalSignature(SigningMaterial material) {
		boolean[] usage = material.certificate().getKeyUsage();
		return usage != null && usage.length > DIGITAL_SIGNATURE && usage[DIGITAL_SIGNATURE];
	}

}
