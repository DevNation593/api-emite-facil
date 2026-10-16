package com.example.einvoice.certificate;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Certificados de firma de los emisores. El archivo PKCS#12 y su contraseña se guardan cifrados y
 * solo se descifran en memoria en el momento de firmar; nunca salen por la API, ni van a la
 * bitácora, ni a los logs.
 */
@Service
public class CertificateService {

	public record CertificateView(UUID id, UUID taxpayerId, String subject, String issuer, String serialNumber,
			String fingerprintSha256, OffsetDateTime validFrom, OffsetDateTime validTo, String status) {
	}

	private record Encrypted(UUID id, byte[] encryptedP12, byte[] encryptedPassword) {
	}

	private static final String VIEW_COLUMNS = "id, taxpayer_id, subject, issuer, serial_number, fingerprint_sha256,"
			+ " valid_from, valid_to, status";

	private final JdbcClient jdbc;

	private final SecretCipher cipher;

	private final AuditLog audit;

	public CertificateService(JdbcClient jdbc, SecretCipher cipher, AuditLog audit) {
		this.jdbc = jdbc;
		this.cipher = cipher;
		this.audit = audit;
	}

	/** Valida el certificado, lo guarda cifrado y lo deja como el activo del emisor. */
	@Transactional
	public CertificateView upload(TenantAccess access, UUID taxpayerId, byte[] p12, char[] password) {
		access.requireWrite();
		// El bloqueo del emisor ordena dos cargas simultáneas: solo puede quedar un certificado activo.
		lockTaxpayer(access.tenantId(), taxpayerId);
		SigningMaterial material;
		try {
			material = Pkcs12Inspector.inspect(p12, password, Instant.now());
		}
		catch (CertificateRejectedException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ex.errorCode(), ex.getMessage());
		}
		X509Certificate certificate = material.certificate();
		UUID id = UUID.randomUUID();
		byte[] passwordBytes = toBytes(password);
		byte[] encryptedP12 = cipher.encrypt(p12, secretContext(access.tenantId(), taxpayerId, id, "p12"));
		byte[] encryptedPassword = cipher.encrypt(passwordBytes,
				secretContext(access.tenantId(), taxpayerId, id, "password"));
		Arrays.fill(passwordBytes, (byte) 0);
		String fingerprint = fingerprint(certificate);
		OffsetDateTime validTo = utc(certificate.getNotAfter().toInstant());

		jdbc.sql("UPDATE certificates SET status = 'REPLACED' WHERE taxpayer_id = :taxpayer AND status = 'ACTIVE'")
			.param("taxpayer", taxpayerId)
			.update();
		jdbc.sql("""
				INSERT INTO certificates (id, tenant_id, taxpayer_id, subject, issuer, serial_number,
				 fingerprint_sha256, valid_from, valid_to, status, encrypted_p12, encrypted_password, created_by)
				VALUES (:id, :tenant, :taxpayer, :subject, :issuer, :serial, :fingerprint, :validFrom, :validTo,
				 'ACTIVE', :encryptedP12, :encryptedPassword, :createdBy)
				""")
			.param("id", id)
			.param("tenant", access.tenantId())
			.param("taxpayer", taxpayerId)
			.param("subject", certificate.getSubjectX500Principal().getName())
			.param("issuer", certificate.getIssuerX500Principal().getName())
			.param("serial", certificate.getSerialNumber().toString())
			.param("fingerprint", fingerprint)
			.param("validFrom", utc(certificate.getNotBefore().toInstant()))
			.param("validTo", validTo)
			.param("encryptedP12", encryptedP12)
			.param("encryptedPassword", encryptedPassword)
			.param("createdBy", access.userId())
			.update();
		audit.record(access, "CERTIFICATE_UPLOADED", "CERTIFICATE", id,
				Map.of("taxpayerId", taxpayerId.toString(), "fingerprintSha256", fingerprint, "validTo",
						validTo.toString()));
		return view(access.tenantId(), id);
	}

	/** Metadatos de los certificados del emisor, el más reciente primero. Nunca el contenido. */
	@Transactional(readOnly = true)
	public List<CertificateView> list(TenantAccess access, UUID taxpayerId) {
		boolean exists = jdbc.sql("SELECT count(*) FROM taxpayers WHERE id = :id AND tenant_id = :tenant")
			.param("id", taxpayerId)
			.param("tenant", access.tenantId())
			.query(Long.class)
			.single() > 0;
		if (!exists) {
			throw ApiException.notFound();
		}
		return jdbc.sql("SELECT " + VIEW_COLUMNS + " FROM certificates"
				+ " WHERE tenant_id = :tenant AND taxpayer_id = :taxpayer ORDER BY created_at DESC, id")
			.param("tenant", access.tenantId())
			.param("taxpayer", taxpayerId)
			.query(CertificateView.class)
			.list();
	}

	/**
	 * Clave y certificado activos del emisor, listos para firmar. La vigencia se vuelve a comprobar
	 * contra el instante indicado: un certificado pudo caducar después de cargarse. No abre ni marca
	 * transacción: el rechazo es un resultado que quien llama registra y confirma.
	 */
	public SigningMaterial signingMaterial(UUID tenantId, UUID taxpayerId, Instant now) {
		Optional<Encrypted> active = jdbc.sql("""
				SELECT id, encrypted_p12, encrypted_password FROM certificates
				WHERE tenant_id = :tenant AND taxpayer_id = :taxpayer AND status = 'ACTIVE'
				""").param("tenant", tenantId).param("taxpayer", taxpayerId).query(Encrypted.class).optional();
		Encrypted encrypted = active.orElseThrow(() -> new CertificateRejectedException("CERTIFICATE_MISSING",
				"El emisor no tiene un certificado de firma activo"));
		byte[] p12 = cipher.decrypt(encrypted.encryptedP12(),
				secretContext(tenantId, taxpayerId, encrypted.id(), "p12"));
		byte[] passwordBytes = cipher.decrypt(encrypted.encryptedPassword(),
				secretContext(tenantId, taxpayerId, encrypted.id(), "password"));
		char[] password = toChars(passwordBytes);
		try {
			return Pkcs12Inspector.inspect(p12, password, now);
		}
		finally {
			Arrays.fill(p12, (byte) 0);
			Arrays.fill(passwordBytes, (byte) 0);
			Arrays.fill(password, '\0');
		}
	}

	private void lockTaxpayer(UUID tenantId, UUID taxpayerId) {
		jdbc.sql("SELECT id FROM taxpayers WHERE id = :id AND tenant_id = :tenant FOR UPDATE")
			.param("id", taxpayerId)
			.param("tenant", tenantId)
			.query(UUID.class)
			.optional()
			.orElseThrow(ApiException::notFound);
	}

	private CertificateView view(UUID tenantId, UUID id) {
		return jdbc.sql("SELECT " + VIEW_COLUMNS + " FROM certificates WHERE id = :id AND tenant_id = :tenant")
			.param("id", id)
			.param("tenant", tenantId)
			.query(CertificateView.class)
			.single();
	}

	/**
	 * Datos autenticados junto con cada secreto. Lo ligan a su empresa, su emisor, su certificado y
	 * su campo: quien pueda escribir en la base no puede hacer que el certificado de un emisor firme
	 * por otro, ni intercambiar el archivo con la contraseña.
	 */
	public static byte[] secretContext(UUID tenantId, UUID taxpayerId, UUID certificateId, String field) {
		return ("certificate|" + tenantId + "|" + taxpayerId + "|" + certificateId + "|" + field)
			.getBytes(StandardCharsets.UTF_8);
	}

	private static String fingerprint(X509Certificate certificate) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
		}
		catch (NoSuchAlgorithmException | CertificateEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static byte[] toBytes(char[] chars) {
		ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}

	private static char[] toChars(byte[] bytes) {
		CharBuffer buffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
		char[] chars = new char[buffer.remaining()];
		buffer.get(chars);
		return chars;
	}

}
