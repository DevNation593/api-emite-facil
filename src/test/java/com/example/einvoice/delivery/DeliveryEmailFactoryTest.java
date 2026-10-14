package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.einvoice.xml.InvoiceXmlFixtures;

class DeliveryEmailFactoryTest {

	private static final byte[] PDF = "%PDF-1.5 ride".getBytes(StandardCharsets.UTF_8);

	private static final byte[] XML = "<autorizacion/>".getBytes(StandardCharsets.UTF_8);

	private static RideData ride(InvoiceXmlFixtures.Builder builder) {
		return new RideData(builder.build(), InvoiceXmlFixtures.ACCESS_KEY, OffsetDateTime.parse("2026-10-07T20:15:30Z"),
				"cliente@example.test", Map.of());
	}

	@Test
	void subjectTextAndAttachments() {
		DeliveryEmail email = DeliveryEmailFactory.build("facturas@plataforma.test", ride(InvoiceXmlFixtures.builder()),
				"cliente@example.test", PDF, XML);

		assertThat(email.from()).isEqualTo("facturas@plataforma.test");
		assertThat(email.fromName()).isEqualTo("Andina");
		assertThat(email.to()).isEqualTo("cliente@example.test");
		assertThat(email.subject()).isEqualTo("Factura 001-002-000000123 de Andina");
		assertThat(email.text()).contains("María Pérez", "Comercial Andina S.A.", "1790012345001",
				"001-002-000000123", "07/10/2026", "37.00", InvoiceXmlFixtures.ACCESS_KEY, "RIDE", "XML");
		assertThat(email.attachments()).hasSize(2);
		assertThat(email.attachments().get(0).filename()).isEqualTo("factura-001-002-000000123.pdf");
		assertThat(email.attachments().get(0).contentType()).isEqualTo("application/pdf");
		assertThat(email.attachments().get(0).content()).isEqualTo(PDF);
		assertThat(email.attachments().get(1).filename()).isEqualTo(InvoiceXmlFixtures.ACCESS_KEY + ".xml");
		assertThat(email.attachments().get(1).contentType()).isEqualTo("application/xml");
		assertThat(email.attachments().get(1).content()).isEqualTo(XML);
	}

	@Test
	void issuerWithoutTradeNameUsesItsLegalName() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.tradeName = null;

		DeliveryEmail email = DeliveryEmailFactory.build("facturas@plataforma.test", ride(builder),
				"cliente@example.test", PDF, XML);

		assertThat(email.subject()).isEqualTo("Factura 001-002-000000123 de Comercial Andina S.A.");
		assertThat(email.fromName()).isEqualTo("Comercial Andina S.A.");
	}

	@Test
	void namesCannotInjectHeaders() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.tradeName = "Andina\r\nBcc: x@evil.test\u0000" + "x".repeat(400);

		DeliveryEmail email = DeliveryEmailFactory.build("facturas@plataforma.test", ride(builder),
				"cliente@example.test", PDF, XML);

		assertThat(email.subject()).doesNotContain("\r", "\n", "\u0000").startsWith("Factura 001-002-000000123 de Andina Bcc:");
		assertThat(email.subject().length()).isLessThanOrEqualTo(200);
		assertThat(email.fromName()).doesNotContain("\r", "\n", "\u0000");
		assertThat(email.fromName().length()).isLessThanOrEqualTo(120);
	}

}
