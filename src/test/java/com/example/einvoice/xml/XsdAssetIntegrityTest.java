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
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards docs/xsd-source-registry.csv: every packaged schema asset is registered and its bytes
 * match the registered SHA-256.
 */
class XsdAssetIntegrityTest {

	static final Path REPO = Path.of("").toAbsolutePath().normalize();

	static final Path REGISTRY = REPO.resolve("docs/xsd-source-registry.csv");

	static final Path XSD_ROOT = REPO.resolve("src/main/resources/xsd");

	record Row(String localPath, String sha256) {
	}

	static List<Row> rows() throws IOException {
		return Files.readAllLines(REGISTRY).stream().skip(1).filter(l -> !l.isBlank())
				.map(l -> l.split(",", 9)).map(c -> new Row(c[3], c[6])).toList();
	}

	@Test
	void registryListsTwelveAssets() throws IOException {
		assertThat(rows()).hasSize(12);
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

}
