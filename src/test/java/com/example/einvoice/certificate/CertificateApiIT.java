package com.example.einvoice.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.support.TestData;
import com.jayway.jsonpath.JsonPath;

@ExtendWith(OutputCaptureExtension.class)
class CertificateApiIT extends AbstractIntegrationTest {

	static final String CERTIFICATES = "/api/v1/certificates";

	@Autowired
	CertificateService certificates;

	String owner;

	UUID tenant;

	UUID taxpayer;

	@BeforeEach
	void taxpayerOfNewTenant() throws Exception {
		owner = newUserToken();
		tenant = createTenant(owner);
		taxpayer = createTaxpayer(owner, TestData.ruc());
	}

	static String base64(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	String body(UUID taxpayerId, byte[] p12, String password) {
		return "{\"taxpayerId\":\"" + taxpayerId + "\",\"p12\":\"" + base64(p12) + "\",\"password\":\"" + password
				+ "\"}";
	}

	long stored() {
		return jdbc.queryForObject("SELECT count(*) FROM certificates WHERE taxpayer_id = ?", Long.class, taxpayer);
	}

	@Test
	void ownerUploadsCertificate() throws Exception {
		String response = mvc
			.perform(json(post(CERTIFICATES), owner, body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.taxpayerId").value(taxpayer.toString()))
			.andExpect(jsonPath("$.subject").value(org.hamcrest.Matchers.containsString("CN=Emisor de Pruebas firma")))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.fingerprintSha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
			.andExpect(jsonPath("$.validFrom").isNotEmpty())
			.andExpect(jsonPath("$.validTo").isNotEmpty())
			.andExpect(jsonPath("$.serialNumber").isNotEmpty())
			.andExpect(jsonPath("$.p12").doesNotExist())
			.andExpect(jsonPath("$.password").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(response, "$.id"));

		mvc.perform(as(get(CERTIFICATES).param("taxpayerId", taxpayer.toString()), memberToken(tenant, "AUDITOR")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(id.toString()))
			.andExpect(jsonPath("$[0].encryptedP12").doesNotExist());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'CERTIFICATE_UPLOADED' AND entity_id = ? AND tenant_id = ?",
				Integer.class, id, tenant))
			.isEqualTo(1);
	}

	@Test
	void billerCannotUpload() throws Exception {
		mvc.perform(json(post(CERTIFICATES), memberToken(tenant, "BILLER"),
				body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ROLE_FORBIDDEN"));
		assertThat(stored()).isZero();
	}

	@Test
	void secondUploadReplacesTheFirst() throws Exception {
		mvc.perform(json(post(CERTIFICATES), owner, body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD)))
			.andExpect(status().isCreated());
		mvc.perform(json(post(CERTIFICATES), owner,
				body(taxpayer, TestCertificates.anotherValid(), TestCertificates.PASSWORD)))
			.andExpect(status().isCreated());

		assertThat(jdbc.queryForList("SELECT status FROM certificates WHERE taxpayer_id = ? ORDER BY created_at",
				String.class, taxpayer))
			.containsExactly("REPLACED", "ACTIVE");
		SigningMaterial material = certificates.signingMaterial(tenant, taxpayer, Instant.now());
		SigningMaterial second = Pkcs12Inspector.inspect(TestCertificates.anotherValid(),
				TestCertificates.PASSWORD.toCharArray(), Instant.now());
		assertThat(material.certificate()).isEqualTo(second.certificate());
	}

	@Test
	void rejectedCertificatesStoreNothing() throws Exception {
		Object[][] cases = { { TestCertificates.valid(), "otra-clave", "CERTIFICATE_UNREADABLE" },
				{ "no es un p12".getBytes(StandardCharsets.UTF_8), TestCertificates.PASSWORD, "CERTIFICATE_UNREADABLE" },
				{ TestCertificates.expired(), TestCertificates.PASSWORD, "CERTIFICATE_EXPIRED" },
				{ TestCertificates.notYetValid(), TestCertificates.PASSWORD, "CERTIFICATE_NOT_YET_VALID" },
				{ TestCertificates.withoutPrivateKey(), TestCertificates.PASSWORD, "CERTIFICATE_NO_PRIVATE_KEY" },
				{ TestCertificates.twoSigningKeys(), TestCertificates.PASSWORD, "CERTIFICATE_AMBIGUOUS" },
				{ TestCertificates.shortKey(), TestCertificates.PASSWORD, "CERTIFICATE_KEY_UNSUPPORTED" },
				{ TestCertificates.mismatchedKey(), TestCertificates.PASSWORD, "CERTIFICATE_KEY_MISMATCH" } };
		for (Object[] rejected : cases) {
			mvc.perform(json(post(CERTIFICATES), owner, body(taxpayer, (byte[]) rejected[0], (String) rejected[1])))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errorCode").value(rejected[2]));
		}
		assertThat(stored()).isZero();
	}

	@Test
	void rejectsMalformedRequests() throws Exception {
		String valid = body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD);
		mvc.perform(json(post(CERTIFICATES), owner, valid.replace(base64(TestCertificates.valid()), "###no-base64###")))
			.andExpect(status().isBadRequest());
		mvc.perform(json(post(CERTIFICATES), owner, body(taxpayer, new byte[70_000], TestCertificates.PASSWORD)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		mvc.perform(json(post(CERTIFICATES), owner, body(taxpayer, TestCertificates.valid(), "")))
			.andExpect(status().isBadRequest());
		mvc.perform(as(get(CERTIFICATES), owner)).andExpect(status().isBadRequest());
		assertThat(stored()).isZero();
	}

	@Test
	void secretNeverLeavesTheServer(CapturedOutput output) throws Exception {
		String p12 = base64(TestCertificates.valid());
		String response = mvc
			.perform(json(post(CERTIFICATES), owner, body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		mvc.perform(json(post(CERTIFICATES), owner, body(taxpayer, TestCertificates.valid(), "clave-equivocada-xyz")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.doesNotContain("clave-equivocada-xyz"));
		String listed = mvc.perform(as(get(CERTIFICATES).param("taxpayerId", taxpayer.toString()), owner))
			.andReturn()
			.getResponse()
			.getContentAsString();

		String marker = p12.substring(0, 40);
		assertThat(response).doesNotContain(TestCertificates.PASSWORD, marker);
		assertThat(listed).doesNotContain(TestCertificates.PASSWORD, marker);
		assertThat(output.getAll()).doesNotContain(TestCertificates.PASSWORD, marker, "clave-equivocada-xyz");
		assertThat(jdbc.queryForObject("SELECT string_agg(metadata::text, ' ') FROM audit_events WHERE tenant_id = ?",
				String.class, tenant))
			.doesNotContain(TestCertificates.PASSWORD, marker);

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT encrypted_p12, encrypted_password FROM certificates WHERE taxpayer_id = ? AND status = 'ACTIVE'",
				taxpayer);
		byte[] storedP12 = (byte[]) row.get("encrypted_p12");
		byte[] storedPassword = (byte[]) row.get("encrypted_password");
		assertThat(storedP12).isNotEqualTo(TestCertificates.valid());
		assertThat(new String(storedP12, StandardCharsets.ISO_8859_1))
			.doesNotContain(new String(TestCertificates.valid(), StandardCharsets.ISO_8859_1).substring(0, 64));
		assertThat(new String(storedPassword, StandardCharsets.ISO_8859_1)).doesNotContain(TestCertificates.PASSWORD);
	}

	@Test
	void certificateOfAnotherTenantIsNotFound() throws Exception {
		mvc.perform(json(post(CERTIFICATES), owner, body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD)))
			.andExpect(status().isCreated());
		String other = newUserToken();
		UUID otherTenant = createTenant(other);

		mvc.perform(json(post(CERTIFICATES), other, body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD)))
			.andExpect(status().isNotFound());
		mvc.perform(as(get(CERTIFICATES).param("taxpayerId", taxpayer.toString()), other))
			.andExpect(status().isNotFound());
		assertThatThrownBy(() -> certificates.signingMaterial(otherTenant, taxpayer, Instant.now()))
			.isInstanceOfSatisfying(CertificateRejectedException.class,
					ex -> assertThat(ex.errorCode()).isEqualTo("CERTIFICATE_MISSING"));
		assertThat(stored()).isEqualTo(1);
	}

	@Test
	void badCertificateDoesNotReplaceAWorkingOne() throws Exception {
		mvc.perform(json(post(CERTIFICATES), owner, body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD)))
			.andExpect(status().isCreated());
		mvc.perform(json(post(CERTIFICATES), owner,
				body(taxpayer, TestCertificates.mismatchedKey(), TestCertificates.PASSWORD)))
			.andExpect(status().isUnprocessableEntity());
		assertThat(jdbc.queryForList("SELECT status FROM certificates WHERE taxpayer_id = ?", String.class, taxpayer))
			.containsExactly("ACTIVE");
		assertThat(certificates.signingMaterial(tenant, taxpayer, Instant.now())).isNotNull();
	}

	@Test
	void ciphertextIsBoundToItsIssuerAndField() throws Exception {
		mvc.perform(json(post(CERTIFICATES), owner, body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD)))
			.andExpect(status().isCreated());

		// Los dos secretos de la misma fila no son intercambiables entre sí.
		jdbc.update("UPDATE certificates SET encrypted_p12 = encrypted_password, encrypted_password = encrypted_p12"
				+ " WHERE taxpayer_id = ?", taxpayer);
		assertThatThrownBy(() -> certificates.signingMaterial(tenant, taxpayer, Instant.now()))
			.isInstanceOf(SecretCipherException.class);
		jdbc.update("UPDATE certificates SET encrypted_p12 = encrypted_password, encrypted_password = encrypted_p12"
				+ " WHERE taxpayer_id = ?", taxpayer);
		assertThat(certificates.signingMaterial(tenant, taxpayer, Instant.now())).isNotNull();

		// Quien pueda escribir en la base no puede reasignar el certificado a otro emisor u otra empresa.
		String other = newUserToken();
		UUID otherTenant = createTenant(other);
		UUID otherTaxpayer = createTaxpayer(other, TestData.ruc());
		jdbc.update("UPDATE certificates SET tenant_id = ?, taxpayer_id = ? WHERE taxpayer_id = ?", otherTenant,
				otherTaxpayer, taxpayer);
		assertThatThrownBy(() -> certificates.signingMaterial(otherTenant, otherTaxpayer, Instant.now()))
			.isInstanceOf(SecretCipherException.class);
	}

	@Test
	void signingMaterialIsRecoverable() throws Exception {
		assertThatThrownBy(() -> certificates.signingMaterial(tenant, taxpayer, Instant.now()))
			.isInstanceOfSatisfying(CertificateRejectedException.class,
					ex -> assertThat(ex.errorCode()).isEqualTo("CERTIFICATE_MISSING"));
		mvc.perform(json(post(CERTIFICATES), owner, body(taxpayer, TestCertificates.valid(), TestCertificates.PASSWORD)))
			.andExpect(status().isCreated());

		SigningMaterial material = certificates.signingMaterial(tenant, taxpayer, Instant.now());
		byte[] message = "comprobante".getBytes(StandardCharsets.UTF_8);
		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(material.privateKey());
		signer.update(message);
		byte[] signature = signer.sign();
		Signature verifier = Signature.getInstance("SHA256withRSA");
		verifier.initVerify(material.certificate());
		verifier.update(message);
		assertThat(verifier.verify(signature)).isTrue();

		// Con el reloj más allá de su vigencia, el certificado guardado ya no sirve para firmar.
		assertThatThrownBy(
				() -> certificates.signingMaterial(tenant, taxpayer, Instant.now().plusSeconds(550L * 24 * 3600)))
			.isInstanceOfSatisfying(CertificateRejectedException.class,
					ex -> assertThat(ex.errorCode()).isEqualTo("CERTIFICATE_EXPIRED"));
	}

}
