package com.example.einvoice.certification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.certification.CertificationMatrix.Case;
import com.example.einvoice.certification.CertificationMatrix.Line;
import com.example.einvoice.certification.EvidenceReport.Header;
import com.example.einvoice.certification.EvidenceReport.Outcome;
import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;

/**
 * Emite la matriz de certificación por la API y la lleva hasta donde el SRI la deje. El mismo
 * recorrido sirve contra el SRI simulado (siempre) y contra el ambiente de pruebas real (a petición).
 */
abstract class AbstractCertificationIT extends AbstractIntegrationTest {

	/** Estados en los que ya no hay nada que esperar. */
	private static final Set<String> SETTLED = Set.of("AUTHORIZED", "RETURNED", "NOT_AUTHORIZED", "XSD_INVALID",
			"SIGNATURE_FAILED");

	@Autowired
	protected DocumentPipeline pipeline;

	@Autowired
	protected JsonMapper json;

	/** Quién emite: empresa ya creada, con su emisor, su certificado y un punto de emisión. */
	protected record Issuer(String owner, UUID tenant, UUID taxpayer, String establishmentCode, String pointCode) {
	}

	/**
	 * Emite cada caso y espera su resultado. Entre intentos se espera {@code pause}, hasta agotar
	 * {@code patience} por comprobante: el SRI real tarda unos segundos en autorizar.
	 */
	protected List<Outcome> run(Issuer issuer, List<Case> cases, Duration patience, Duration pause) throws Exception {
		Map<String, UUID> customers = new HashMap<>();
		List<Outcome> outcomes = new ArrayList<>();
		int productNumber = 0;
		for (Case current : cases) {
			if (current.buyerIdNumber() == null) {
				outcomes.add(Outcome.skipped(current.name(), "Sin identificación de adquirente para este caso"));
				continue;
			}
			String buyer = current.buyerIdType() + ":" + current.buyerIdNumber();
			if (!customers.containsKey(buyer)) {
				customers.put(buyer, createCustomer(issuer.owner(), current.buyerIdType(), current.buyerIdNumber()));
			}
			List<String> lines = new ArrayList<>();
			for (Line line : current.lines()) {
				String sku = "CERT-" + String.format("%03d", ++productNumber);
				UUID product = createProduct(issuer.owner(), sku, line.unitPrice(), line.ivaRateCode());
				lines.add("{\"productId\":\"" + product + "\",\"quantity\":\"" + line.quantity() + "\",\"discount\":\""
						+ line.discount() + "\"}");
			}
			String payments = current.payments()
				.stream()
				.map(payment -> "{\"methodCode\":\"" + payment.methodCode() + "\",\"amount\":\"" + payment.amount() + "\"}")
				.collect(Collectors.joining(","));
			String body = "{\"taxpayerId\":\"" + issuer.taxpayer() + "\",\"establishmentCode\":\""
					+ issuer.establishmentCode() + "\",\"emissionPointCode\":\"" + issuer.pointCode()
					+ "\",\"customerId\":\"" + customers.get(buyer) + "\",\"lines\":[" + String.join(",", lines)
					+ "],\"payments\":[" + payments + "]}";
			String response = mvc.perform(invoiceRequest(issuer.owner(), body))
				.andExpect(status().isAccepted())
				.andReturn()
				.getResponse()
				.getContentAsString();
			UUID document = UUID.fromString(JsonPath.read(response, "$.id"));
			settle(issuer.tenant(), document, patience, pause);
			outcomes.add(outcome(current.name(), document));
		}
		return outcomes;
	}

	private void settle(UUID tenant, UUID document, Duration patience, Duration pause) throws InterruptedException {
		Instant deadline = Instant.now().plus(patience);
		String state = pipeline.advance(tenant, document);
		while (!SETTLED.contains(state) && Instant.now().isBefore(deadline)) {
			Thread.sleep(pause.toMillis());
			state = pipeline.advance(tenant, document);
		}
	}

	private Outcome outcome(String name, UUID document) {
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT access_key, state, total::text AS total, authorization_number, authorized_at::text AS authorized_at"
						+ " FROM fiscal_documents WHERE id = ?",
				document);
		List<String> hashes = jdbc.queryForList(
				"SELECT sha256 FROM document_artifacts WHERE document_id = ? AND kind = 'XML_SIGNED'", String.class,
				document);
		// De cada mensaje del SRI, su código y su tipo: el texto puede nombrar al adquirente.
		List<String> messages = jdbc.queryForList("""
				SELECT DISTINCT m.identifier || ' ' || coalesce(m.type, '')
				  FROM sri_messages m JOIN sri_attempts a ON a.id = m.attempt_id
				 WHERE a.document_id = ? ORDER BY 1
				""", String.class, document);
		return new Outcome(name, (String) row.get("access_key"), (String) row.get("state"), (String) row.get("total"),
				hashes.isEmpty() ? null : hashes.get(0), (String) row.get("authorization_number"),
				(String) row.get("authorized_at"), messages, null);
	}

	protected Path writeEvidence(Path directory, String environment, String sriHost, List<Outcome> outcomes)
			throws IOException {
		String schemaVersion = jdbc.queryForObject(
				"SELECT coalesce(max(schema_version), 'sin generar') FROM fiscal_documents WHERE access_key = ANY (?)",
				String.class, (Object) outcomes.stream().map(Outcome::accessKey).filter(key -> key != null).toArray(String[]::new));
		Header header = new Header(Instant.now(), environment, sriHost, schemaVersion, "v2.34 (julio 2026)", commit());
		return EvidenceReport.write(directory, header, outcomes, json);
	}

	/** Versión del código con la que se obtuvo la evidencia. */
	private static String commit() {
		String fromCi = System.getenv("GITHUB_SHA");
		if (fromCi != null && !fromCi.isBlank()) {
			return fromCi;
		}
		try {
			Process process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes()).strip();
			return process.waitFor() == 0 && output.matches("[0-9a-f]{40}") ? output : "desconocida";
		}
		catch (IOException | InterruptedException ex) {
			return "desconocida";
		}
	}

	/** Empresa nueva con su emisor, creada por la API. */
	protected UUID createTaxpayerWith(String owner, String taxpayerJson) throws Exception {
		String body = mvc.perform(json(post("/api/v1/taxpayers"), owner, taxpayerJson))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

}
