package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SriSoapSamples;
import com.example.einvoice.support.TestCertificates;

/** Datos del RIDE leídos del comprobante autorizado. */
class RideDataIT extends AbstractIntegrationTest {

	@Autowired
	DocumentPipeline pipeline;

	@Autowired
	FiscalDocumentRepository documents;

	BillingScenario s;

	UUID document;

	@BeforeEach
	void invoiceOfAnIssuerWithCertificate() throws Exception {
		s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
	}

	@Test
	void authorizedInvoiceHasRideData() {
		assertThat(pipeline.advance(s.tenant(), document)).isEqualTo("AUTHORIZED");
		String key = jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class, document);

		RideData data = documents.rideData(s.tenant(), document).orElseThrow();

		assertThat(data.authorizationNumber()).isEqualTo(key);
		assertThat(data.authorizedAt()).isEqualTo(OffsetDateTime.parse(SriSoapSamples.AUTHORIZATION_DATE));
		assertThat(data.customerEmail()).isEqualTo("cliente@example.test");
		assertThat(data.paymentMethodNames()).containsEntry("20", "OTROS CON UTILIZACIÓN DEL SISTEMA FINANCIERO");
		assertThat(data.invoice().accessKey()).isEqualTo(key);
		assertThat(data.invoice().issuerRuc()).isEqualTo(s.ruc());
		assertThat(data.invoice().total()).isEqualByComparingTo("23.00");
		assertThat(data.invoice().lines()).hasSize(1);
		// Con esos datos el RIDE se genera.
		assertThat(RidePdfBuilder.build(data)).isNotEmpty();
	}

	@Test
	void unauthorizedOrForeignDocumentHasNone() throws Exception {
		assertThat(documents.rideData(s.tenant(), document)).isEmpty();

		pipeline.advance(s.tenant(), document);
		UUID otherTenant = createTenant(newUserToken());

		assertThat(documents.rideData(otherTenant, document)).isEmpty();
		assertThat(documents.rideData(s.tenant(), UUID.randomUUID())).isEmpty();
	}

}
