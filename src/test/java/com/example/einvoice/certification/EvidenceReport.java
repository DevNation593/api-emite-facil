package com.example.einvoice.certification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Evidencia de una corrida de la matriz (docs/12, "Evidencias"): fecha, ambiente, versiones y, por
 * caso, clave de acceso, estado, huella del XML firmado, autorización y códigos de los mensajes del
 * SRI. No lleva nombres ni identificaciones de adquirentes.
 */
final class EvidenceReport {

	record Header(Instant generatedAt, String environment, String sriHost, String schemaVersion, String technicalSheet,
			String commit) {
	}

	record Outcome(String name, String accessKey, String state, String total, String signedXmlSha256,
			String authorizationNumber, String authorizedAt, List<String> sriMessages, String note) {

		static Outcome skipped(String name, String note) {
			return new Outcome(name, null, "OMITIDO", null, null, null, null, List.of(), note);
		}

	}

	private EvidenceReport() {
	}

	/** Escribe evidence.json y evidence.md en el directorio y devuelve la ruta del JSON. */
	static Path write(Path directory, Header header, List<Outcome> outcomes, JsonMapper json) throws IOException {
		Files.createDirectories(directory);
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("generatedAt", header.generatedAt().toString());
		document.put("environment", header.environment());
		document.put("sriHost", header.sriHost());
		document.put("schemaVersion", header.schemaVersion());
		document.put("technicalSheet", header.technicalSheet());
		document.put("commit", header.commit());
		document.put("cases", outcomes);
		Path file = directory.resolve("evidence.json");
		Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(document), StandardCharsets.UTF_8);

		StringBuilder markdown = new StringBuilder();
		markdown.append("# Evidencia de certificación\n\n");
		markdown.append("- Fecha: ").append(header.generatedAt()).append('\n');
		markdown.append("- Ambiente: ").append(header.environment()).append(" (").append(header.sriHost()).append(")\n");
		markdown.append("- Esquema de factura: ").append(header.schemaVersion()).append('\n');
		markdown.append("- Ficha técnica: ").append(header.technicalSheet()).append('\n');
		markdown.append("- Versión del sistema: ").append(header.commit()).append("\n\n");
		markdown.append("| Caso | Estado | Clave de acceso | Total | SHA-256 del XML firmado | Autorización | Mensajes del SRI |\n");
		markdown.append("|---|---|---|---|---|---|---|\n");
		for (Outcome outcome : outcomes) {
			markdown.append("| ").append(outcome.name());
			markdown.append(" | ").append(outcome.state());
			markdown.append(" | ").append(text(outcome.accessKey()));
			markdown.append(" | ").append(text(outcome.total()));
			markdown.append(" | ").append(text(outcome.signedXmlSha256()));
			markdown.append(" | ").append(outcome.authorizationNumber() == null ? text(outcome.note())
					: outcome.authorizationNumber() + " (" + outcome.authorizedAt() + ")");
			markdown.append(" | ").append(outcome.sriMessages().isEmpty() ? "ninguno" : String.join(", ", outcome.sriMessages()));
			markdown.append(" |\n");
		}
		Files.writeString(directory.resolve("evidence.md"), markdown, StandardCharsets.UTF_8);
		return file;
	}

	private static String text(String value) {
		return value == null ? "—" : value;
	}

}
