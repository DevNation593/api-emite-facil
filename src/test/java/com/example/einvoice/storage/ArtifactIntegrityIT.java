package com.example.einvoice.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.example.einvoice.observability.OperationalMetrics;
import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestCertificates;

import io.micrometer.core.instrument.MeterRegistry;

/** El verificador recalcula la huella de cada artefacto guardado y señala el que ya no coincide. */
@ExtendWith(OutputCaptureExtension.class)
class ArtifactIntegrityIT extends AbstractIntegrationTest {

	/** Más que todos los artefactos que las demás pruebas hayan dejado en la base compartida. */
	static final int EVERYTHING = 1_000_000;

	@Autowired
	ArtifactIntegrityVerifier verifier;

	@Autowired
	DocumentPipeline pipeline;

	@Autowired
	OperationalMetrics metrics;

	@Autowired
	MeterRegistry registry;

	@Autowired
	ThreadPoolTaskScheduler scheduler;

	List<UUID> archivedDocumentArtifacts() throws Exception {
		BillingScenario s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		String state = "";
		for (int step = 0; step < 10 && !"AUTHORIZED".equals(state); step++) {
			state = pipeline.advance(s.tenant(), document);
		}
		assertThat(state).isEqualTo("AUTHORIZED");
		List<UUID> ids = jdbc.queryForList("SELECT id FROM document_artifacts WHERE document_id = ? ORDER BY kind",
				UUID.class, document);
		assertThat(ids).hasSizeGreaterThanOrEqualTo(3);
		return ids;
	}

	Boolean verdict(UUID artifact) {
		List<Boolean> rows = jdbc.queryForList("SELECT ok FROM artifact_integrity WHERE artifact_id = ?", Boolean.class,
				artifact);
		return rows.isEmpty() ? null : rows.get(0);
	}

	void tamper(UUID artifact, String sql) {
		jdbc.execute("ALTER TABLE document_artifacts DISABLE TRIGGER document_artifacts_immutable");
		try {
			jdbc.update(sql, artifact);
		}
		finally {
			jdbc.execute("ALTER TABLE document_artifacts ENABLE TRIGGER document_artifacts_immutable");
		}
	}

	double corruptGauge() {
		metrics.refresh();
		return registry.get("einvoice.artifacts.corrupt").gauge().value();
	}

	@Test
	void intactArtifactsAreMarkedAsVerified() throws Exception {
		List<UUID> artifacts = archivedDocumentArtifacts();
		assertThat(artifacts).allSatisfy(id -> assertThat(verdict(id)).isNull());

		ArtifactIntegrityVerifier.Result result = verifier.verifyNext(EVERYTHING);

		assertThat(result.checked()).isGreaterThanOrEqualTo(artifacts.size());
		assertThat(artifacts).allSatisfy(id -> assertThat(verdict(id)).isTrue());
	}

	@Test
	void anAlteredArtifactIsReportedWithoutItsContent(CapturedOutput output) throws Exception {
		List<UUID> artifacts = archivedDocumentArtifacts();
		UUID altered = artifacts.get(0);
		verifier.verifyNext(EVERYTHING);
		double before = corruptGauge();

		// Alguien con acceso de dueño a la base retira el disparador y cambia un byte.
		tamper(altered, "UPDATE document_artifacts SET content = content || '\\x20'::bytea WHERE id = ?");
		try {
			ArtifactIntegrityVerifier.Result result = verifier.verifyNext(EVERYTHING);

			assertThat(result.corrupt()).isGreaterThanOrEqualTo(1);
			assertThat(verdict(altered)).isFalse();
			assertThat(artifacts.subList(1, artifacts.size())).allSatisfy(id -> assertThat(verdict(id)).isTrue());
			assertThat(corruptGauge()).isEqualTo(before + 1);
			// El aviso nombra el artefacto, no lo copia.
			assertThat(output.getAll()).contains(altered.toString()).doesNotContain("<factura");
		}
		finally {
			tamper(altered, "UPDATE document_artifacts SET content = substring(content FROM 1 FOR length(content) - 1)"
					+ " WHERE id = ?");
		}

		// Corregido el contenido, la siguiente pasada lo da por bueno.
		verifier.verifyNext(EVERYTHING);
		assertThat(verdict(altered)).isTrue();
		assertThat(corruptGauge()).isEqualTo(before);
	}

	@Test
	void whatIsStillWaitingForItsFirstVerificationIsMeasured() throws Exception {
		verifier.verifyNext(EVERYTHING);
		assertThat(registry.get("einvoice.artifacts.unverified").gauge().value()).isZero();
		List<UUID> fresh = archivedDocumentArtifacts();

		// Una pasada que no alcanza a todo deja a la vista cuánto queda y desde cuándo espera.
		verifier.verifyNext(1);

		assertThat(registry.get("einvoice.artifacts.unverified").gauge().value()).isEqualTo(fresh.size() - 1);
		assertThat(registry.get("einvoice.artifacts.unverified.oldest.seconds").gauge().value()).isPositive();

		verifier.verifyNext(EVERYTHING);
		assertThat(registry.get("einvoice.artifacts.unverified").gauge().value()).isZero();
		assertThat(registry.get("einvoice.artifacts.unverified.oldest.seconds").gauge().value()).isZero();
	}

	@Test
	void aLongPassCannotHoldUpTheRestOfTheScheduledWork() {
		// Con un solo hilo, mientras dura una pasada no se publicaría el outbox ni se reintentaría nada.
		assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(4);
	}

	@Test
	void eachPassStartsWithWhatWasVerifiedLongestAgo() throws Exception {
		List<UUID> older = archivedDocumentArtifacts();
		verifier.verifyNext(EVERYTHING);
		List<UUID> fresh = archivedDocumentArtifacts();

		// Lo nunca verificado va primero.
		assertThat(verifier.verifyNext(fresh.size()).checked()).isEqualTo(fresh.size());
		assertThat(fresh).allSatisfy(id -> assertThat(verdict(id)).isTrue());

		// Después, lo que lleva más tiempo sin verificarse.
		UUID stale = older.get(0);
		jdbc.update("UPDATE artifact_integrity SET verified_at = now() - interval '30 days' WHERE artifact_id = ?", stale);
		verifier.verifyNext(1);
		assertThat(jdbc.queryForObject(
				"SELECT verified_at > now() - interval '1 minute' FROM artifact_integrity WHERE artifact_id = ?",
				Boolean.class, stale))
			.isTrue();
	}

}
