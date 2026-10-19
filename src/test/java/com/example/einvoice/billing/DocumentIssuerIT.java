package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.einvoice.catalog.CustomerService;
import com.example.einvoice.orchestration.DocumentProcessor;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.support.TestData;
import com.example.einvoice.tenancy.Role;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentType;

import tools.jackson.databind.json.JsonMapper;

/** La emisión común: el XML se valida antes de confirmar, y lo emitido es lo que el worker genera después. */
class DocumentIssuerIT extends AbstractIntegrationTest {

	/** Contenido de la liquidación de prueba: cualquier objeto con un campo. */
	record Sample(String text) {
	}

	/** Liquidación de prueba cuyo XML nunca pasa el XSD: solo tiene el elemento raíz. */
	@TestConfiguration
	static class BrokenLiquidationConfig {

		@Bean
		DocumentKind<Sample> brokenLiquidationKind(FiscalDocumentRepository documents, JsonMapper json) {
			return new JsonContentKind<>(Sample.class, documents, json) {

				@Override
				public DocumentType type() {
					return DocumentType.PURCHASE_LIQUIDATION;
				}

				@Override
				public DocumentSchema schema() {
					return DocumentSchema.PURCHASE_LIQUIDATION_1_1_0;
				}

				@Override
				public String displayName() {
					return "Liquidación de prueba";
				}

				@Override
				public String fileStem() {
					return "liquidacion-prueba";
				}

				@Override
				public String auditReserved() {
					return "SAMPLE_RESERVED";
				}

				@Override
				public byte[] buildXml(DocumentEnvelope envelope, Sample content) {
					return "<liquidacionCompra/>".getBytes(StandardCharsets.UTF_8);
				}

				@Override
				public byte[] buildRide(RideEnvelope ride, Sample content) {
					throw new UnsupportedOperationException();
				}

			};
		}

	}

	@Autowired
	DocumentIssuer issuer;

	@Autowired
	DocumentKinds kinds;

	@Autowired
	CustomerService customers;

	@Autowired
	DocumentProcessor processor;

	@Autowired
	ArtifactStore artifacts;

	@MockitoSpyBean
	DocumentXmlCheck xmlCheck;

	BillingScenario s;

	@BeforeEach
	void scenario() throws Exception {
		s = newBillingScenario();
	}

	@SuppressWarnings("unchecked")
	DocumentKind<Sample> brokenKind() {
		return (DocumentKind<Sample>) kinds.find(DocumentType.PURCHASE_LIQUIDATION).orElseThrow();
	}

	UUID issueBroken(TenantAccess access, String idempotencyKey, String requestHash) {
		DocumentIssuer.IssueRequest request = new DocumentIssuer.IssueRequest(s.taxpayer(), "001", "001", null, null,
				null);
		return issuer.issue(access, idempotencyKey, requestHash, request,
				context -> new DocumentIssuer.Preparation<>(brokenKind(), customers.get(access, s.customer()),
						new DocumentEnvelope.Totals(null, null, null, BigDecimal.ONE), null, new Sample("x")));
	}

	long count(String sql) {
		return jdbc.queryForObject(sql, Long.class, s.tenant());
	}

	@Test
	void invalidXmlIsRejectedWithoutTrace() {
		// El contador de liquidaciones del punto ya existe: la reserva lo avanzaría si no se revirtiera.
		jdbc.update("""
				INSERT INTO document_sequences (tenant_id, emission_point_id, environment, document_type, next_value)
				VALUES (?, ?, '1', '03', 5)
				""", s.tenant(), s.point());
		TenantAccess access = new TenantAccess(TestData.user(jdbc), s.tenant(), Role.OWNER);
		String key = UUID.randomUUID().toString();

		assertThatThrownBy(() -> issueBroken(access, key, "hash-1"))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
				assertThat(ex.errorCode()).isEqualTo("DOCUMENT_XML_INVALID");
				assertThat(ex.getMessage()).isNotBlank().hasSizeLessThanOrEqualTo(500);
			});

		assertThat(jdbc.queryForObject(
				"SELECT next_value FROM document_sequences WHERE emission_point_id = ? AND document_type = '03'",
				Long.class, s.point()))
			.isEqualTo(5L);
		assertThat(count("SELECT count(*) FROM fiscal_documents WHERE tenant_id = ?")).isZero();
		assertThat(count("SELECT count(*) FROM outbox_events WHERE tenant_id = ?")).isZero();
		assertThat(count("SELECT count(*) FROM idempotency_records WHERE tenant_id = ?")).isZero();

		// La clave quedó libre: otra petición con ella vuelve a emitirse (y vuelve a fallar por el XML), sin conflicto.
		assertThatThrownBy(() -> issueBroken(access, key, "hash-2"))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.errorCode()).isEqualTo("DOCUMENT_XML_INVALID"));
	}

	@Test
	void issuedXmlEqualsTheWorkerXml() throws Exception {
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		UUID document = issueInvoice(s.owner(), s.invoiceJson(s.customer(),
				"{\"productId\":\"" + s.product() + "\",\"quantity\":\"2\",\"unitPrice\":\"1.5\"}", "3.45"));

		ArgumentCaptor<byte[]> xml = ArgumentCaptor.forClass(byte[].class);
		verify(xmlCheck).problem(xml.capture(), any());
		byte[] issued = xml.getValue();

		processor.generateAndSign(s.tenant(), document);

		byte[] generated = artifacts.find(s.tenant(), document, ArtifactKind.XML_UNSIGNED).orElseThrow().content();
		assertThat(new String(generated, StandardCharsets.UTF_8)).isEqualTo(new String(issued, StandardCharsets.UTF_8));
		assertThat(generated).isEqualTo(issued);
	}

	@Test
	void replacementMustBeOfTheSameType() throws Exception {
		UUID liquidation = TestData.document(jdbc, s.tenant(), s.taxpayer(), s.point(), s.customer(), 1,
				TestData.accessKey(), "03", new BigDecimal("1.50"), "{\"text\":\"x\"}");
		jdbc.update("UPDATE fiscal_documents SET state = 'XSD_INVALID' WHERE id = ?", liquidation);
		String body = s.invoiceJson("1", "11.50")
			.replaceFirst("\\{", "{\"replacesDocumentId\":\"" + liquidation + "\",");

		mvc.perform(invoiceRequest(s.owner(), body))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("REPLACED_DOCUMENT_OTHER_TYPE"))
			.andExpect(jsonPath("$.detail").value("El comprobante que se reemplaza es de otro tipo"));
		assertThat(count("SELECT count(*) FROM fiscal_documents WHERE tenant_id = ?")).isEqualTo(1);
	}

	@Test
	void outboxEventIsDocumentReserved() throws Exception {
		UUID document = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));

		assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ?", String.class,
				document))
			.containsExactly("DOCUMENT_RESERVED");
		List<Map<String, Object>> audit = jdbc.queryForList(
				"SELECT action FROM audit_events WHERE tenant_id = ? AND entity_id = ?", s.tenant(), document);
		assertThat(audit).extracting(row -> row.get("action")).containsExactly("INVOICE_RESERVED");
	}

}
