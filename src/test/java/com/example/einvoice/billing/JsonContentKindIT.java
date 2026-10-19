package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;
import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentType;

/** El contenido propio de un tipo viaja como JSON por la columna content sin perder la escala de los importes. */
class JsonContentKindIT extends AbstractIntegrationTest {

	record Sample(String text, BigDecimal amount) {
	}

	/** Tipo de prueba: solo ejercita la base; no genera XML ni RIDE. */
	static final class SampleKind extends JsonContentKind<Sample> {

		SampleKind(FiscalDocumentRepository documents) {
			super(Sample.class, documents);
		}

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
			return "Muestra";
		}

		@Override
		public String fileStem() {
			return "muestra";
		}

		@Override
		public String auditReserved() {
			return "SAMPLE_RESERVED";
		}

		@Override
		public byte[] buildXml(DocumentEnvelope envelope, Sample content) {
			throw new UnsupportedOperationException();
		}

		@Override
		public byte[] buildRide(RideEnvelope ride, Sample content) {
			throw new UnsupportedOperationException();
		}

	}

	@Autowired
	FiscalDocumentRepository documents;

	private record Inserted(UUID tenant, UUID document) {
	}

	private Inserted insert(String type, String content) {
		UUID tenant = TestData.tenant(jdbc);
		UUID taxpayer = TestData.taxpayer(jdbc, tenant, TestData.ruc());
		UUID establishment = TestData.establishment(jdbc, tenant, taxpayer, "001");
		UUID point = TestData.emissionPoint(jdbc, tenant, establishment, "001");
		UUID customer = TestData.customer(jdbc, tenant, "05", "1712345678");
		return new Inserted(tenant, TestData.document(jdbc, tenant, taxpayer, point, customer, 1,
				TestData.accessKey(), type, new BigDecimal("1.50"), content));
	}

	@Test
	void contentRoundTripKeepsScale() {
		SampleKind kind = new SampleKind(documents);
		Sample sample = new Sample("a", new BigDecimal("1.50"));
		String content = kind.contentJson(sample);
		assertThat(content).contains("\"amount\":\"1.50\"");

		Inserted inserted = insert("03", content);
		Sample loaded = kind.loadContent(inserted.tenant(), inserted.document());
		assertThat(loaded).isEqualTo(sample);
		assertThat(loaded.amount().scale()).isEqualTo(2);
	}

	@Test
	void missingContentFails() {
		SampleKind kind = new SampleKind(documents);
		Inserted invoice = insert("01", null);
		assertThatThrownBy(() -> kind.loadContent(invoice.tenant(), invoice.document()))
			.isInstanceOf(IllegalStateException.class);
		Inserted sample = insert("03", kind.contentJson(new Sample("b", BigDecimal.ONE)));
		assertThatThrownBy(() -> kind.loadContent(UUID.randomUUID(), sample.document()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void unknownPropertyFailsToLoad() {
		SampleKind kind = new SampleKind(documents);
		Inserted inserted = insert("03", "{\"text\":\"a\",\"amount\":\"1.50\",\"renamed\":1}");
		assertThatThrownBy(() -> kind.loadContent(inserted.tenant(), inserted.document()))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	void reloadedKeepsValueAndScale() {
		SampleKind kind = new SampleKind(documents);
		Sample sample = new Sample("a", new BigDecimal("1.50"));
		Sample reloaded = kind.reloaded(sample);
		assertThat(reloaded).isEqualTo(sample);
		assertThat(reloaded.amount().scale()).isEqualTo(2);
	}

}
