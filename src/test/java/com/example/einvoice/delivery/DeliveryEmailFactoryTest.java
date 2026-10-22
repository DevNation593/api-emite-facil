package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.RideEnvelope;
import com.example.einvoice.xml.DocumentType;
import com.example.einvoice.xml.InvoiceXmlFixtures;

class DeliveryEmailFactoryTest {

	private static final byte[] PDF = "%PDF-1.5 ride".getBytes(StandardCharsets.UTF_8);

	private static final byte[] XML = "<autorizacion/>".getBytes(StandardCharsets.UTF_8);

	private static RideEnvelope ride(String tradeName, boolean withTotals) {
		DocumentEnvelope envelope = new DocumentEnvelope(DocumentType.INVOICE, "1",
				new DocumentEnvelope.Issuer("Comercial Andina S.A.", tradeName, "1790012345001", "Quito", "Quito",
						false, null, null, "NONE", null),
				InvoiceXmlFixtures.ACCESS_KEY, "001", "002", 123, LocalDate.of(2026, 10, 7),
				new DocumentEnvelope.Recipient("05", "1712345678", "María Pérez", "Quito", "cliente@example.test",
						null),
				withTotals ? new DocumentEnvelope.Totals(new BigDecimal("33.00"), BigDecimal.ZERO,
						new BigDecimal("4.00"), new BigDecimal("37.00")) : null,
				null, "1790099999001");
		return new RideEnvelope(envelope, InvoiceXmlFixtures.ACCESS_KEY, OffsetDateTime.parse("2026-10-07T20:15:30Z"));
	}

	private static RideEnvelope ride(String tradeName) {
		return ride(tradeName, true);
	}

	@Test
	void subjectTextAndAttachments() {
		DeliveryEmail email = DeliveryEmailFactory.build("facturas@plataforma.test", ride("Andina"), "Factura", "factura",
				"cliente@example.test", PDF, XML);

		assertThat(email.from()).isEqualTo("facturas@plataforma.test");
		assertThat(email.fromName()).isEqualTo("Andina");
		assertThat(email.to()).isEqualTo("cliente@example.test");
		assertThat(email.subject()).isEqualTo("Factura 001-002-000000123 de Andina");
		assertThat(email.text()).contains("María Pérez", "Comercial Andina S.A.", "1790012345001",
				"001-002-000000123", "07/10/2026", "37.00", InvoiceXmlFixtures.ACCESS_KEY, "RIDE", "XML", "le ha emitido la factura electrónica 001-002-000000123");
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
		DeliveryEmail email = DeliveryEmailFactory.build("facturas@plataforma.test", ride(null), "Factura", "factura",
				"cliente@example.test", PDF, XML);

		assertThat(email.subject()).isEqualTo("Factura 001-002-000000123 de Comercial Andina S.A.");
		assertThat(email.fromName()).isEqualTo("Comercial Andina S.A.");
	}

	@Test
	void namesCannotInjectHeaders() {
		DeliveryEmail email = DeliveryEmailFactory.build("facturas@plataforma.test",
				ride("Andina\r\nBcc: x@evil.test\u0000" + "x".repeat(400)), "Factura", "factura",
				"cliente@example.test", PDF, XML);

		assertThat(email.subject()).doesNotContain("\r", "\n", "\u0000").startsWith("Factura 001-002-000000123 de Andina Bcc:");
		assertThat(email.subject().length()).isLessThanOrEqualTo(200);
		assertThat(email.fromName()).doesNotContain("\r", "\n", "\u0000");
		assertThat(email.fromName().length()).isLessThanOrEqualTo(120);
	}

	@Test
	void otherTypeUsesItsName() {
		DeliveryEmail email = DeliveryEmailFactory.build("facturas@plataforma.test", ride("Andina"),
				"Nota de crédito", "nota-credito", "cliente@example.test", PDF, XML);

		assertThat(email.subject()).startsWith("Nota de crédito ");
		assertThat(email.text()).contains("le ha emitido la nota de crédito electrónica");
		assertThat(email.attachments().get(0).filename()).isEqualTo("nota-credito-001-002-000000123.pdf");
	}

	@Test
	void noTotalLineWithoutTotal() {
		DeliveryEmail email = DeliveryEmailFactory.build("facturas@plataforma.test", ride("Andina", false),
				"Guía de remisión", "guia-remision", "cliente@example.test", PDF, XML);

		assertThat(email.text()).doesNotContain("Valor total");
	}

}
