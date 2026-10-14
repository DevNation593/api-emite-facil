package com.example.einvoice.certification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.certification.CertificationMatrix.Case;
import com.example.einvoice.certification.EvidenceReport.Outcome;
import com.example.einvoice.signature.XadesVerifier;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.support.TestData;
import com.example.einvoice.xml.InvoiceSchemas;
import com.example.einvoice.xml.SchemaVersion;
import com.jayway.jsonpath.JsonPath;

/**
 * La matriz de certificación contra el SRI simulado. Corre siempre: prueba que cada caso se emite,
 * se firma, se autoriza y deja su evidencia. Lo que solo el SRI real puede decir (si acepta la
 * firma y los datos) lo dice SriCertificationIT.
 */
class CertificationMatrixIT extends AbstractCertificationIT {

	static final Path EVIDENCE = Path.of("target", "certification", "simulado");

	static final String BUYER_RUC = "1790011111001";

	static final String BUYER_CEDULA = "1712345678";

	@Autowired
	ArtifactStore artifacts;

	@Autowired
	InvoiceSchemas schemas;

	@Autowired
	XadesVerifier verifier;

	@Test
	void everyCaseIsAuthorizedAndLeavesItsEvidence() throws Exception {
		String owner = newUserToken();
		UUID tenant = createTenant(owner);
		UUID taxpayer = createTaxpayer(owner, TestData.ruc());
		createEmissionPoint(owner, createEstablishment(owner, taxpayer, "001"), "001");
		uploadCertificate(owner, taxpayer, TestCertificates.valid());
		List<Case> cases = CertificationMatrix.cases(BUYER_RUC, BUYER_CEDULA);

		List<Outcome> outcomes = run(new Issuer(owner, tenant, taxpayer, "001", "001"), cases, Duration.ofSeconds(20),
				Duration.ofMillis(50));
		Path file = writeEvidence(EVIDENCE, "1 (pruebas)", "SRI simulado", outcomes);

		assertThat(outcomes).hasSameSizeAs(cases);
		for (int i = 0; i < cases.size(); i++) {
			Outcome outcome = outcomes.get(i);
			assertThat(outcome.state()).as(outcome.name()).isEqualTo("AUTHORIZED");
			// El total lo calcula el sistema; el esperado está fijado a mano en la matriz.
			assertThat(outcome.total()).as(outcome.name()).isEqualTo(cases.get(i).expectedTotal());
			assertThat(outcome.accessKey()).matches("[0-9]{49}");
			assertThat(outcome.authorizationNumber()).isEqualTo(outcome.accessKey());
			UUID document = jdbc.queryForObject("SELECT id FROM fiscal_documents WHERE access_key = ?", UUID.class,
					outcome.accessKey());
			byte[] signed = artifacts.find(tenant, document, ArtifactKind.XML_SIGNED).orElseThrow().content();
			assertThat(ArtifactStore.sha256(signed)).isEqualTo(outcome.signedXmlSha256());
			assertThat(verifier.verify(signed).errors()).as(outcome.name()).isEmpty();
			schemas.get(SchemaVersion.V1_1_0).newValidator().validate(new StreamSource(new ByteArrayInputStream(signed)));
		}
		// Seis facturas seguidas del mismo punto: seis secuenciales consecutivos.
		assertThat(outcomes.stream().map(outcome -> Integer.parseInt(outcome.accessKey().substring(30, 39))).toList())
			.containsExactly(1, 2, 3, 4, 5, 6);

		String evidence = Files.readString(file);
		assertThat(JsonPath.<List<Object>>read(evidence, "$.cases")).hasSize(cases.size());
		assertThat(JsonPath.<String>read(evidence, "$.schemaVersion")).isEqualTo("1.1.0");
		assertThat(JsonPath.<String>read(evidence, "$.commit")).isNotBlank();
		assertThat(JsonPath.<List<String>>read(evidence, "$.cases[*].signedXmlSha256")).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
		String table = Files.readString(EVIDENCE.resolve("evidence.md"));
		assertThat(table).contains("| Factura a consumidor final | AUTHORIZED |");
		// La evidencia no identifica a ningún adquirente.
		for (String text : List.of(evidence, table)) {
			assertThat(text).doesNotContain(BUYER_RUC, BUYER_CEDULA, "AB123456", "María", "cliente@example.test");
		}
	}

	@Test
	void aCaseWithoutABuyerIsReportedAsSkippedNotAsPassed() throws Exception {
		String owner = newUserToken();
		UUID tenant = createTenant(owner);
		UUID taxpayer = createTaxpayer(owner, TestData.ruc());
		createEmissionPoint(owner, createEstablishment(owner, taxpayer, "001"), "001");
		uploadCertificate(owner, taxpayer, TestCertificates.valid());

		List<Outcome> outcomes = run(new Issuer(owner, tenant, taxpayer, "001", "001"),
				CertificationMatrix.cases(BUYER_RUC, null), Duration.ofSeconds(20), Duration.ofMillis(50));

		assertThat(outcomes).filteredOn(outcome -> "OMITIDO".equals(outcome.state()))
			.extracting(Outcome::name)
			.containsExactly("Factura a una cédula, con descuento", "Factura con IVA 15 % e IVA 0 %");
		assertThat(outcomes).filteredOn(outcome -> "AUTHORIZED".equals(outcome.state())).hasSize(4);
	}

}
