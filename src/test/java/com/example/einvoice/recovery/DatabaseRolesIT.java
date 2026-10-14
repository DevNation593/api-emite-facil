package com.example.einvoice.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import com.example.einvoice.support.AbstractIntegrationTest;

/**
 * Con infra/postgres/roles.sql, quien migra es dueño de las tablas y la aplicación opera con un
 * usuario que solo lee y escribe filas: no puede retirar las defensas de la base.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseRolesIT extends AbstractIntegrationTest {

	static final String MIGRATOR_PASSWORD = "clave-del-migrador-de-prueba";

	static final String APP_PASSWORD = "clave-de-la-aplicacion-de-prueba";

	@Autowired
	PostgreSQLContainer postgres;

	JdbcTemplate app;

	String database;

	@BeforeAll
	void aDatabaseMigratedByOneRoleAndUsedByAnother() throws Exception {
		database = "roles_" + UUID.randomUUID().toString().replace("-", "");
		String superuser = postgres.getUsername();
		exec("createdb", "-U", superuser, database);
		postgres.copyFileToContainer(MountableFile.forHostPath("../infra/postgres/roles.sql"), "/tmp/roles.sql");
		exec("psql", "-U", superuser, "-d", database, "-v", "ON_ERROR_STOP=1", "-v", "db=" + database, "-v",
				"migrator_password=" + MIGRATOR_PASSWORD, "-v", "app_password=" + APP_PASSWORD, "-f", "/tmp/roles.sql");

		String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
		Flyway.configure()
			.dataSource(url, "einvoice_migrator", MIGRATOR_PASSWORD)
			.locations("classpath:db/migration")
			.load()
			.migrate();
		app = new JdbcTemplate(new DriverManagerDataSource(url, "einvoice_app", APP_PASSWORD));
	}

	@Test
	void aBackupRestoresIntoTheTwoRoleSetup() throws Exception {
		UUID tenant = app.queryForObject("INSERT INTO tenants (name) VALUES ('Respaldada') RETURNING id", UUID.class);
		String superuser = postgres.getUsername();
		String restoredName = "roles_restaurada_" + UUID.randomUUID().toString().replace("-", "");
		exec("pg_dump", "-U", superuser, "-Fc", "-f", "/tmp/" + restoredName + ".dump", database);

		// El orden que documenta infra/backup/restore.sh: base vacía, roles, y restaurar como quien migra.
		exec("createdb", "-U", superuser, restoredName);
		exec("psql", "-U", superuser, "-d", restoredName, "-v", "ON_ERROR_STOP=1", "-v", "db=" + restoredName, "-v",
				"migrator_password=" + MIGRATOR_PASSWORD, "-v", "app_password=" + APP_PASSWORD, "-f", "/tmp/roles.sql");
		postgres.copyFileToContainer(MountableFile.forHostPath("../infra/backup/restore.sh"), "/tmp/restore.sh");
		ExecResult restore = postgres.execInContainer("env", "PGUSER=einvoice_migrator",
				"PGPASSWORD=" + MIGRATOR_PASSWORD, "PGHOST=127.0.0.1", "bash", "-c",
				"cd /tmp && sha256sum " + restoredName + ".dump > " + restoredName + ".dump.sha256 && bash /tmp/restore.sh /tmp/"
						+ restoredName + ".dump " + restoredName);
		assertThat(restore.getExitCode()).as(restore.getStdout() + restore.getStderr()).isZero();

		String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + restoredName);
		// Quien migra es dueño de lo restaurado: la aplicación arrancaría y podría seguir migrando.
		Flyway flyway = Flyway.configure()
			.dataSource(url, "einvoice_migrator", MIGRATOR_PASSWORD)
			.locations("classpath:db/migration")
			.load();
		flyway.validate();
		assertThat(flyway.info().pending()).isEmpty();
		// Y la aplicación opera con su usuario, que sigue sin poder retirar las defensas.
		JdbcTemplate restored = new JdbcTemplate(new DriverManagerDataSource(url, "einvoice_app", APP_PASSWORD));
		assertThat(restored.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenant))
			.isEqualTo("Respaldada");
		assertThat(restored.update("UPDATE tenants SET name = 'Restaurada' WHERE id = ?", tenant)).isEqualTo(1);
		assertThatThrownBy(() -> restored.execute("TRUNCATE document_artifacts")).rootCause()
			.isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
		assertThatThrownBy(() -> restored.execute("ALTER TABLE document_artifacts DISABLE TRIGGER ALL")).rootCause()
			.isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
	}

	@Test
	void aRestoreThatFailsHalfwayLeavesTheDatabaseEmptyToTryAgain() throws Exception {
		String superuser = postgres.getUsername();
		String restoredName = "roles_fallida_" + UUID.randomUUID().toString().replace("-", "");
		// Un volcado que concede permisos a un rol que el servidor de destino no tiene: los datos se
		// cargan y la restauración falla después, al llegar a los permisos.
		exec("psql", "-U", superuser, "-d", database, "-v", "ON_ERROR_STOP=1", "-c",
				"CREATE ROLE rol_que_no_estara; GRANT SELECT ON tenants TO rol_que_no_estara");
		try {
			exec("pg_dump", "-U", superuser, "-Fc", "-f", "/tmp/" + restoredName + ".dump", database);
		}
		finally {
			exec("psql", "-U", superuser, "-d", database, "-v", "ON_ERROR_STOP=1", "-c",
					"REVOKE ALL ON tenants FROM rol_que_no_estara; DROP ROLE rol_que_no_estara");
		}
		exec("createdb", "-U", superuser, restoredName);
		exec("psql", "-U", superuser, "-d", restoredName, "-v", "ON_ERROR_STOP=1", "-v", "db=" + restoredName, "-v",
				"migrator_password=" + MIGRATOR_PASSWORD, "-v", "app_password=" + APP_PASSWORD, "-f", "/tmp/roles.sql");
		postgres.copyFileToContainer(MountableFile.forHostPath("../infra/backup/restore.sh"), "/tmp/restore.sh");

		ExecResult restore = postgres.execInContainer("env", "PGUSER=einvoice_migrator",
				"PGPASSWORD=" + MIGRATOR_PASSWORD, "PGHOST=127.0.0.1", "bash", "-c",
				"cd /tmp && sha256sum " + restoredName + ".dump > " + restoredName + ".dump.sha256 && bash /tmp/restore.sh /tmp/"
						+ restoredName + ".dump " + restoredName);

		assertThat(restore.getExitCode()).as(restore.getStdout() + restore.getStderr()).isNotZero();
		// Nada quedó a medias: se puede corregir la causa y restaurar de nuevo en la misma base.
		ExecResult tables = postgres.execInContainer("psql", "-U", superuser, "-d", restoredName, "-At", "-c",
				"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'");
		assertThat(tables.getStdout().strip()).isEqualTo("0");
	}

	void exec(String... command) throws Exception {
		ExecResult result = postgres.execInContainer(command);
		assertThat(result.getExitCode()).as(String.join(" ", command) + "\n" + result.getStderr()).isZero();
	}

	void assertDenied(String sql) {
		assertThatThrownBy(() -> app.execute(sql)).as(sql)
			.rootCause()
			.isInstanceOfSatisfying(SQLException.class,
					// 42501: permiso insuficiente.
					error -> assertThat(error.getSQLState()).isEqualTo("42501"));
	}

	@Test
	void theApplicationRoleReadsAndWritesRows() {
		UUID tenant = app.queryForObject("INSERT INTO tenants (name) VALUES ('Comercial Andina') RETURNING id",
				UUID.class);

		assertThat(app.update("UPDATE tenants SET name = 'Andina' WHERE id = ?", tenant)).isEqualTo(1);
		assertThat(app.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenant)).isEqualTo("Andina");
		assertThat(app.queryForObject("SELECT count(*) FROM tax_rates", Integer.class)).isPositive();
		assertThat(app.update("DELETE FROM tenants WHERE id = ?", tenant)).isEqualTo(1);
	}

	@Test
	void theApplicationRoleCannotRemoveTheDatabaseDefences() {
		assertDenied("ALTER TABLE document_artifacts DISABLE TRIGGER ALL");
		assertDenied("DROP TRIGGER document_artifacts_immutable ON document_artifacts");
		assertDenied("TRUNCATE document_artifacts");
		assertDenied("ALTER TABLE taxpayers DROP CONSTRAINT taxpayers_production_needs_activation");
		assertDenied("DROP TABLE audit_events");
		assertDenied("CREATE TABLE intrusa (id int)");
	}

}
