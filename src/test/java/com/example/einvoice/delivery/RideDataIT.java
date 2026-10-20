package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.InvoiceBody;
import com.example.einvoice.billing.InvoiceKind;
import com.example.einvoice.billing.RideEnvelope;
import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SriSoapSamples;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.xml.DocumentType;

/** Sobre del RIDE leído del comprobante autorizado. */
class RideDataIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

	@Autowired
	FiscalDocumentRepository documents;

	@Autowired
	InvoiceKind invoiceKind;

	BillingScenario s;

	UUID document;

	@BeforeEach
	void invoiceOfAnIssuerWithCertificate() throws Exception {
		s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
	}

	@Test
	void authorizedInvoiceHasRideEnvelope() {
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");
		String key = jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class, document);

		RideEnvelope ride = documents.rideEnvelope(s.tenant(), document).orElseThrow();
		InvoiceBody body = invoiceKind.loadContent(s.tenant(), document);

		assertThat(ride.authorizationNumber()).isEqualTo(key);
		assertThat(ride.authorizedAt()).isEqualTo(OffsetDateTime.parse(SriSoapSamples.AUTHORIZATION_DATE));
		assertThat(ride.envelope().type()).isEqualTo(DocumentType.INVOICE);
		assertThat(ride.envelope().recipient().email()).isEqualTo("cliente@example.test");
		assertThat(documents.paymentMethodNames()).containsEntry("20", "OTROS CON UTILIZACIÓN DEL SISTEMA FINANCIERO");
		assertThat(ride.envelope().accessKey()).isEqualTo(key);
		assertThat(ride.envelope().issuer().ruc()).isEqualTo(s.ruc());
		assertThat(ride.envelope().totals().total()).isEqualByComparingTo("23.00");
		assertThat(ride.envelope().totals().totalTax()).isNotNull();
		assertThat(ride.envelope().modified()).isNull();
		assertThat(body.lines()).hasSize(1);
		// Con el sobre y el cuerpo el XML y el RIDE se generan.
		assertThat(new String(invoiceKind.buildXml(ride.envelope(), body), java.nio.charset.StandardCharsets.UTF_8))
			.contains(key);
		assertThat(invoiceKind.buildRide(ride, body)).isNotEmpty();
	}

	@Test
	void unauthorizedOrForeignDocumentHasNone() throws Exception {
		assertThat(documents.rideEnvelope(s.tenant(), document)).isEmpty();
		assertThat(documents.envelope(s.tenant(), document)).isPresent();

		pipeline.advance(s.tenant(), document);
		UUID otherTenant = createTenant(newUserToken());

		assertThat(documents.rideEnvelope(s.tenant(), document)).isPresent();
		assertThat(documents.rideEnvelope(s.tenant(), UUID.randomUUID())).isEmpty();
		assertThat(documents.rideEnvelope(otherTenant, document)).isEmpty();
		assertThat(documents.envelope(otherTenant, document)).isEmpty();
	}

}
