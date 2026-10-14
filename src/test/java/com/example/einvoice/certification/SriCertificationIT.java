package com.example.einvoice.certification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.TestPropertySource;

import com.example.einvoice.certification.EvidenceReport.Outcome;
import com.example.einvoice.support.RealSri;

/**
 * La matriz de certificación contra el ambiente de PRUEBAS del SRI (celcer.sri.gob.ec). Transmite
 * comprobantes de verdad, así que solo corre a petición:
 *
 * <pre>
 * ./mvnw -B verify -Pcertification
 * </pre>
 *
 * con el certificado y los datos del emisor en variables de entorno (docs/15-produccion.md). Sin
 * ellas se salta diciendo cuáles faltan. Deja la evidencia en target/certification/sri-pruebas.
 * Con {@code -Deinvoice.certification.simulated=true} hace el mismo recorrido contra el SRI simulado:
 * un ensayo de la corrida, sin transmitir nada.
 */
@RealSri
@EnabledIfSystemProperty(named = "einvoice.certification", matches = "true")
@ExtendWith(SriCertificationIT.RequiresIssuerAndCertificate.class)
@TestPropertySource(properties = {
		// El RUC del proveedor que irá en los comprobantes es el real, o ninguno: nunca el de las pruebas.
		"einvoice.provider.ruc=${SRI_CERT_PROVIDER_RUC:}" })
class SriCertificationIT extends AbstractCertificationIT {

	static final List<String> REQUIRED = List.of("SRI_CERT_P12_PATH", "SRI_CERT_P12_PASSWORD", "SRI_CERT_RUC",
			"SRI_CERT_LEGAL_NAME", "SRI_CERT_ADDRESS");

	static final Path EVIDENCE = Path.of("target", "certification", SIMULATED_CERTIFICATION ? "ensayo" : "sri-pruebas");

	static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value.strip();
	}

	/**
	 * Sin el certificado y los datos del emisor la corrida se salta, diciendo qué falta, antes de
	 * arrancar nada. Como condición (y no como suposición) queda en el informe como prueba omitida.
	 */
	static class RequiresIssuerAndCertificate implements ExecutionCondition {

		@Override
		public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
			List<String> missing = REQUIRED.stream().filter(name -> env(name, null) == null).toList();
			return missing.isEmpty() ? ConditionEvaluationResult.enabled("Emisor y certificado configurados")
					: ConditionEvaluationResult
						.disabled("Certificación omitida: faltan las variables " + String.join(", ", missing));
		}

	}

	static String quoted(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	@Test
	void theSriTestEnvironmentAuthorizesTheMatrix() throws Exception {
		String owner = newUserToken();
		UUID tenant = createTenant(owner);
		String ruc = env("SRI_CERT_RUC", null);
		String address = env("SRI_CERT_ADDRESS", null);
		String tradeName = env("SRI_CERT_TRADE_NAME", null);
		String specialTaxpayer = env("SRI_CERT_SPECIAL_TAXPAYER", null);
		String withholdingAgent = env("SRI_CERT_WITHHOLDING_AGENT", null);
		UUID taxpayer = createTaxpayerWith(owner,
				"{\"ruc\":" + quoted(ruc) + ",\"legalName\":" + quoted(env("SRI_CERT_LEGAL_NAME", null))
						+ (tradeName == null ? "" : ",\"tradeName\":" + quoted(tradeName)) + ",\"headOfficeAddress\":"
						+ quoted(address) + ",\"defaultEnvironment\":\"1\",\"accountingRequired\":"
						+ Boolean.parseBoolean(env("SRI_CERT_ACCOUNTING_REQUIRED", "false")) + ",\"rimpeRegime\":"
						+ quoted(env("SRI_CERT_RIMPE", "NONE"))
						+ (specialTaxpayer == null ? "" : ",\"specialTaxpayerCode\":" + quoted(specialTaxpayer))
						+ (withholdingAgent == null ? "" : ",\"withholdingAgentCode\":" + quoted(withholdingAgent)) + "}");
		String establishmentCode = env("SRI_CERT_ESTABLISHMENT", "001");
		String pointCode = env("SRI_CERT_POINT", "001");
		String establishment = mvc
			.perform(json(post("/api/v1/taxpayers/" + taxpayer + "/establishments"), owner, "{\"code\":"
					+ quoted(establishmentCode) + ",\"address\":" + quoted(env("SRI_CERT_ESTABLISHMENT_ADDRESS", address)) + "}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID point = createEmissionPoint(owner, UUID.fromString(com.jayway.jsonpath.JsonPath.read(establishment, "$.id")),
				pointCode);
		// El SRI recuerda los secuenciales ya usados: cada corrida empieza en uno nuevo.
		long sequence = Long.parseLong(env("SRI_CERT_START_SEQUENCE",
				Long.toString(Duration.between(Instant.parse("2026-01-01T00:00:00Z"), Instant.now()).toMinutes())));
		mvc.perform(json(put("/api/v1/emission-points/" + point + "/sequence"), owner,
				"{\"environment\":\"1\",\"documentType\":\"01\",\"nextValue\":" + sequence + "}"))
			.andExpect(status().isOk());
		mvc.perform(json(post("/api/v1/certificates"), owner,
				"{\"taxpayerId\":\"" + taxpayer + "\",\"p12\":\""
						+ Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(env("SRI_CERT_P12_PATH", null))))
						+ "\",\"password\":" + quoted(env("SRI_CERT_P12_PASSWORD", null)) + "}"))
			.andExpect(status().isCreated());

		List<Outcome> outcomes = run(new Issuer(owner, tenant, taxpayer, establishmentCode, pointCode),
				// El propio RUC del emisor sirve de adquirente con RUC; la cédula, si se indica.
				CertificationMatrix.cases(ruc, env("SRI_CERT_BUYER_CEDULA", null)), Duration.ofMinutes(5),
				SIMULATED_CERTIFICATION ? Duration.ofMillis(50) : Duration.ofSeconds(5));
		// La evidencia se escribe pase lo que pase: si el SRI rechaza algo, ahí está el porqué.
		Path file = writeEvidence(EVIDENCE, "1 (pruebas)",
				SIMULATED_CERTIFICATION ? "SRI simulado (ensayo del modo certificación)" : "celcer.sri.gob.ec", outcomes);

		assertThat(outcomes).filteredOn(outcome -> !"OMITIDO".equals(outcome.state()))
			.as("Evidencia en " + file.toAbsolutePath())
			.isNotEmpty()
			.allSatisfy(outcome -> assertThat(outcome.state()).as(outcome.name() + " " + outcome.sriMessages())
				.isEqualTo("AUTHORIZED"));
	}

}
