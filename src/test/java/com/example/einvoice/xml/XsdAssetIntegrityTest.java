package com.example.einvoice.xml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards docs/xsd-source-registry.csv: every packaged schema asset is registered and its bytes
 * match the registered SHA-256.
 */
class XsdAssetIntegrityTest {

	static final Path REPO = Path.of("").toAbsolutePath().normalize();

	static final Path REGISTRY = REPO.resolve("docs/xsd-source-registry.csv");

	static final Path XSD_ROOT = REPO.resolve("src/main/resources/xsd");

	record Row(String assetType, String documentType, String schemaVersion, String localPath, String sha256) {
	}

	static List<Row> rows() throws IOException {
		return Files.readAllLines(REGISTRY).stream().skip(1).filter(l -> !l.isBlank())
				.map(l -> l.split(",", 9)).map(c -> new Row(c[0], c[1], c[2], c[3], c[6])).toList();
	}

	@Test
	void registryListsTwentySevenAssets() throws IOException {
		// 13 XSD, sus 13 XML de ejemplo y el XMLDSig compartido.
		assertThat(rows()).hasSize(27);
		assertThat(rows()).extracting(Row::localPath).doesNotHaveDuplicates();
	}

	@ParameterizedTest
	@MethodSource("rows")
	void fileMatchesRegisteredHash(Row row) throws Exception {
		Path file = REPO.resolve(row.localPath());
		assertThat(file).isRegularFile();
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
		assertThat(HexFormat.of().formatHex(digest)).isEqualTo(row.sha256());
	}

	@Test
	void everyPackagedAssetIsRegistered() throws IOException {
		try (var files = Files.walk(XSD_ROOT)) {
			Set<String> packaged = files.filter(Files::isRegularFile)
					.map(p -> REPO.relativize(p).toString().replace('\\', '/')).collect(Collectors.toSet());
			assertThat(packaged).isEqualTo(rows().stream().map(Row::localPath).collect(Collectors.toSet()));
		}
	}

	@Test
	void xmldsigIsPackagedOnlyOnceInTheSharedFolder() throws IOException {
		try (var files = Files.walk(XSD_ROOT)) {
			assertThat(files.filter(p -> p.getFileName().toString().equals("xmldsig-core-schema.xsd")))
					.containsExactly(XSD_ROOT.resolve("common/xmldsig-core-schema.xsd"));
		}
	}

	@ParameterizedTest
	@EnumSource(DocumentSchema.class)
	void everySchemaIsRegisteredWithItsSample(DocumentSchema schema) throws IOException {
		List<Row> rows = rows();
		String code = schema.type().code();
		assertThat(rows).contains(
				new Row("XSD", code, schema.version(), "src/main/resources/" + schema.xsdResource(),
						sha256Of(rows, schema.xsdResource())),
				new Row("XML-EJEMPLO", code, schema.version(), "src/main/resources/" + schema.sampleResource(),
						sha256Of(rows, schema.sampleResource())));
	}

	private static String sha256Of(List<Row> rows, String resource) {
		return rows.stream().filter(r -> r.localPath().equals("src/main/resources/" + resource)).map(Row::sha256)
				.findFirst().orElse("sin registrar");
	}

}
