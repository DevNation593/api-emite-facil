package com.example.einvoice.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Certificados de prueba generados con la herramienta keytool del JDK que ejecuta las pruebas. Son
 * autofirmados y no sirven ante el SRI: solo ejercitan la lectura, la custodia y la firma.
 */
public final class TestCertificates {

	public static final String PASSWORD = "clave-de-prueba-p12";

	private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

	private TestCertificates() {
	}

	/** RSA 2048 vigente, con uso de clave digitalSignature. */
	public static byte[] valid() {
		return cached("valid", dir -> keyPair(dir, "firma", 2048, "-1d", 365, true));
	}

	/** Otro certificado vigente, con otra clave. */
	public static byte[] anotherValid() {
		return cached("another", dir -> keyPair(dir, "firma", 2048, "-1d", 365, true));
	}

	/** Vigente pero sin la extensión de uso de clave. */
	public static byte[] withoutKeyUsage() {
		return cached("no-usage", dir -> keyPair(dir, "firma", 2048, "-1d", 365, false));
	}

	public static byte[] expired() {
		return cached("expired", dir -> keyPair(dir, "firma", 2048, "-30d", 5, true));
	}

	public static byte[] notYetValid() {
		return cached("future", dir -> keyPair(dir, "firma", 2048, "+5d", 365, true));
	}

	public static byte[] shortKey() {
		return cached("short", dir -> keyPair(dir, "firma", 1024, "-1d", 365, true));
	}

	/** Dos entradas con clave privada, ambas con uso digitalSignature. */
	public static byte[] twoSigningKeys() {
		return cached("two", dir -> {
			keyPair(dir, "firma", 2048, "-1d", 365, true);
			keyPair(dir, "otra", 2048, "-1d", 365, true);
		});
	}

	/** Una clave de firma y otra solo de cifrado: la habitual en certificados ecuatorianos. */
	public static byte[] signingAndEncryptionKeys() {
		return cached("sign-enc", dir -> {
			keyPair(dir, "cifrado", 2048, "-1d", 365, "KeyUsage=keyEncipherment");
			keyPair(dir, "firma", 2048, "-1d", 365, true);
		});
	}

	/** Almacén que solo contiene un certificado de confianza, sin clave privada. */
	public static byte[] withoutPrivateKey() {
		return cached("no-key", dir -> {
			Path source = dir.resolve("source.p12");
			run(dir, "-genkeypair", "-alias", "firma", "-keyalg", "RSA", "-keysize", "2048", "-dname",
					"CN=Sin Clave, O=Pruebas, C=EC", "-validity", "365", "-storetype", "PKCS12", "-keystore",
					source.toString(), "-storepass", PASSWORD);
			run(dir, "-exportcert", "-alias", "firma", "-keystore", source.toString(), "-storepass", PASSWORD,
					"-file", dir.resolve("cert.cer").toString());
			run(dir, "-importcert", "-noprompt", "-alias", "confianza", "-file", dir.resolve("cert.cer").toString(),
					"-storetype", "PKCS12", "-keystore", dir.resolve("store.p12").toString(), "-storepass", PASSWORD);
		});
	}

	/** Clave privada de un certificado guardada junto al certificado de otro. */
	public static byte[] mismatchedKey() {
		// Se generan antes: el mapa no admite calcular una entrada desde dentro del cálculo de otra.
		byte[] firstStore = valid();
		byte[] secondStore = anotherValid();
		return CACHE.computeIfAbsent("mismatch", key -> {
			try {
				char[] password = PASSWORD.toCharArray();
				KeyStore first = KeyStore.getInstance("PKCS12");
				first.load(new ByteArrayInputStream(firstStore), password);
				KeyStore second = KeyStore.getInstance("PKCS12");
				second.load(new ByteArrayInputStream(secondStore), password);
				KeyStore mixed = KeyStore.getInstance("PKCS12");
				mixed.load(null, null);
				mixed.setKeyEntry("firma", second.getKey("firma", password), password,
						new Certificate[] { first.getCertificate("firma") });
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				mixed.store(out, password);
				return out.toByteArray();
			}
			catch (IOException | GeneralSecurityException ex) {
				throw new IllegalStateException(ex);
			}
		});
	}

	private interface Recipe {

		void create(Path dir) throws IOException, InterruptedException;

	}

	private static byte[] cached(String name, Recipe recipe) {
		return CACHE.computeIfAbsent(name, key -> {
			try {
				Path dir = Files.createTempDirectory("einvoice-cert-" + key);
				try {
					recipe.create(dir);
					return Files.readAllBytes(dir.resolve("store.p12"));
				}
				finally {
					try (var files = Files.list(dir)) {
						for (Path file : files.toList()) {
							Files.deleteIfExists(file);
						}
					}
					Files.deleteIfExists(dir);
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		});
	}

	private static void keyPair(Path dir, String alias, int keySize, String startDate, int validityDays,
			boolean digitalSignature) throws IOException, InterruptedException {
		keyPair(dir, alias, keySize, startDate, validityDays, digitalSignature ? "KeyUsage=digitalSignature" : null);
	}

	private static void keyPair(Path dir, String alias, int keySize, String startDate, int validityDays,
			String extension) throws IOException, InterruptedException {
		List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize",
				String.valueOf(keySize), "-sigalg", "SHA256withRSA", "-dname",
				"CN=Emisor de Pruebas " + alias + ", O=Pruebas, C=EC", "-startdate", startDate, "-validity",
				String.valueOf(validityDays), "-storetype", "PKCS12", "-keystore", dir.resolve("store.p12").toString(),
				"-storepass", PASSWORD));
		if (extension != null) {
			args.addAll(List.of("-ext", extension));
		}
		run(dir, args.toArray(String[]::new));
	}

	private static void run(Path dir, String... args) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
		command.addAll(List.of(args));
		Path log = dir.resolve("keytool.log");
		// Sin JAVA_TOOL_OPTIONS: keytool no lo necesita y su aviso ensucia la salida.
		ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile())
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
		builder.environment().remove("JAVA_TOOL_OPTIONS");
		Process process = builder.start();
		if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
			process.destroyForcibly();
			throw new IllegalStateException("keytool falló: " + Files.readString(log));
		}
	}

}
