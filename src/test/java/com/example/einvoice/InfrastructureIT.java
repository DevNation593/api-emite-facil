package com.example.einvoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import com.example.einvoice.support.AbstractIntegrationTest;

class InfrastructureIT extends AbstractIntegrationTest {

	@Test
	void healthReportsEveryDependencyUp() throws Exception {
		mvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components.db.status").value("UP"))
			.andExpect(jsonPath("$.components.rabbit.status").value("UP"))
			.andExpect(jsonPath("$.components.redis.status").value("UP"));
	}

	@Test
	void schemaRegistryMatchesPackagedXsd() throws Exception {
		var rows = jdbc.queryForList("SELECT resource_path, sha256 FROM schema_registry WHERE document_type = '01'");
		assertThat(rows).hasSize(4);
		for (var row : rows) {
			try (var in = getClass().getClassLoader().getResourceAsStream((String) row.get("resource_path"))) {
				byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
				assertThat(HexFormat.of().formatHex(digest)).isEqualTo(row.get("sha256"));
			}
		}
	}

}
