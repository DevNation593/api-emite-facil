package com.example.einvoice.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestCertificates;

/**
 * Respaldo y restauración con las mismas órdenes que infra/backup: lo restaurado conserva los
 * comprobantes, sus artefactos con su huella, las defensas de la base y la numeración.
 */
class RecoveryIT extends AbstractIntegrationTest {

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	DocumentPipeline pipeline;

	void exec(String... command) throws Exception {
		ExecResult result = postgres.execInContainer(command);
		assertThat(result.getExitCode()).as(String.join(" ", command) + "\n" + result.getStderr()).isZero();
	}

	UUID authorizedInvoice(BillingScenario s, String quantity, String amount) throws Exception {
		UUID document = issueInvoice(s.owner(), s.invoiceJson(quantity, amount));
		String state = "";
		for (int step = 0; step < 10 && !"AUTHORIZED".equals(state); step++) {
			state = pipeline.advance(s.tenant(), document);
		}
		assertThat(state).isEqualTo("AUTHORIZED");
		return document;
	}

	/** Huella del contenido de una tabla para una empresa: igual en origen y destino si nada se perdió. */
	static String digest(JdbcTemplate db, String table, String order, UUID tenant) {
		return db.queryForObject("SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY " + order + ")), 'vacía') FROM "
				+ table + " t WHERE tenant_id = ?", String.class, tenant);
	}

	@Test
	void theRestoreScriptFailsWhenWhatItRestoredDoesNotMatchItsHashes() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		UUID document = authorizedInvoice(s, "2", "23.00");
		UUID artifact = jdbc.queryForObject("SELECT id FROM document_artifacts WHERE document_id = ? LIMIT 1", UUID.class,
				document);
		String restoredName = "alterada_" + UUID.randomUUID().toString().replace("-", "");
		String user = postgres.getUsername();
		// Un respaldo tomado de una base en la que alguien ya había alterado un artefacto.
		jdbc.execute("ALTER TABLE document_artifacts DISABLE TRIGGER document_artifacts_immutable");
		try {
			jdbc.update("UPDATE document_artifacts SET content = content || '\\x20'::bytea WHERE id = ?", artifact);
			exec("pg_dump", "-U", user, "-Fc", "-f", "/tmp/" + restoredName + ".dump", postgres.getDatabaseName());
		}
		finally {
			jdbc.update("UPDATE document_artifacts SET content = substring(content FROM 1 FOR length(content) - 1) WHERE id = ?",
					artifact);
			jdbc.execute("ALTER TABLE document_artifacts ENABLE TRIGGER document_artifacts_immutable");
		}
		exec("createdb", "-U", user, restoredName);
		postgres.copyFileToContainer(MountableFile.forHostPath("infra/backup/restore.sh"), "/tmp/restore.sh");

		ExecResult restore = postgres.execInContainer("env", "PGUSER=" + user, "bash", "-c",
				"cd /tmp && sha256sum " + restoredName + ".dump > " + restoredName + ".dump.sha256 && bash /tmp/restore.sh /tmp/"
						+ restoredName + ".dump " + restoredName);

		// Lo restaura (para poder examinarlo) pero no lo da por bueno.
		// Al menos el de esta prueba: otras dejan artefactos alterados en la base compartida.
		assertThat(restore.getStdout()).containsPattern("artefactos cuya huella no coincide: [1-9]");
		assertThat(restore.getExitCode()).as(restore.getStdout() + restore.getStderr()).isNotZero();
	}

	@Test
	void aRestoredBackupKeepsDocumentsArtifactsDefencesAndNumbering() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		authorizedInvoice(s, "2", "23.00");
		UUID last = authorizedInvoice(s, "1", "11.50");

		String restoredName = "restaurada_" + UUID.randomUUID().toString().replace("-", "");
		String user = postgres.getUsername();
		exec("pg_dump", "-U", user, "-Fc", "-f", "/tmp/" + restoredName + ".dump", postgres.getDatabaseName());
		exec("createdb", "-U", user, restoredName);
		exec("pg_restore", "-U", user, "--no-owner", "--exit-on-error", "-d", restoredName,
				"/tmp/" + restoredName + ".dump");

		DriverManagerDataSource source = new DriverManagerDataSource(
				postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + restoredName), user,
				postgres.getPassword());
		JdbcTemplate restored = new JdbcTemplate(source);

		// Nada de la empresa se perdió ni cambió.
		for (String[] table : new String[][] { { "taxpayers", "id" }, { "certificates", "id" },
				{ "fiscal_documents", "id" }, { "fiscal_document_lines", "id" }, { "state_events", "id" },
				{ "audit_events", "id" }, { "document_sequences", "emission_point_id" } }) {
			assertThat(digest(restored, table[0], table[1], s.tenant())).as(table[0])
				.isEqualTo(digest(jdbc, table[0], table[1], s.tenant()))
				.isNotEqualTo("vacía");
		}

		// Cada artefacto está y su contenido sigue correspondiendo a su huella.
		List<String> original = jdbc.queryForList(
				"SELECT kind || ':' || sha256 FROM document_artifacts WHERE document_id = ? ORDER BY kind", String.class,
				last);
		assertThat(original).hasSizeGreaterThanOrEqualTo(3);
		assertThat(restored.queryForList(
				"SELECT kind || ':' || encode(sha256(content), 'hex') FROM document_artifacts WHERE document_id = ?"
						+ " ORDER BY kind",
				String.class, last))
			.isEqualTo(original);

		// Las defensas de la base viajan con el respaldo.
		assertThatThrownBy(() -> restored.update("UPDATE document_artifacts SET sha256 = sha256 WHERE document_id = ?", last))
			.hasMessageContaining("solo inserción");
		assertThatThrownBy(() -> restored.update("DELETE FROM document_artifacts WHERE document_id = ?", last))
			.hasMessageContaining("solo inserción");
		assertThatThrownBy(() -> restored.update("DELETE FROM audit_events WHERE tenant_id = ?", s.tenant()))
			.hasMessageContaining("solo inserción");
		assertThatThrownBy(
				() -> restored.update("UPDATE taxpayers SET default_environment = '2' WHERE id = ?", s.taxpayer()))
			.hasMessageContaining("taxpayers_production_needs_activation");

		// La numeración continúa donde quedó: el siguiente secuencial no repite ninguno.
		Long next = restored.queryForObject(
				"SELECT next_value FROM document_sequences WHERE emission_point_id = ? AND environment = '1'", Long.class,
				s.point());
		Long highest = restored.queryForObject("SELECT max(sequence) FROM fiscal_documents WHERE emission_point_id = ?",
				Long.class, s.point());
		assertThat(next).isEqualTo(highest + 1);

		// Y la aplicación arrancaría sobre ella: el historial de migraciones está completo.
		Flyway flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
		flyway.validate();
		assertThat(flyway.info().pending()).isEmpty();
	}

}
