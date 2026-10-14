package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.TestData;
import com.example.einvoice.xml.InvoiceXmlData;
import com.jayway.jsonpath.JsonPath;

/** El XML se generará de lo que el documento guardó al emitirse, no de las tablas vivas. */
class IssuerSnapshotIT extends AbstractIntegrationTest {

	@Autowired
	FiscalDocumentRepository documents;

	@Test
	void documentFreezesIssuerData() throws Exception {
		BillingScenario s = newBillingScenario();
		UUID id = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));

		jdbc.update("UPDATE taxpayers SET legal_name = 'Otra S.A.', trade_name = 'Otra', head_office_address = 'Cuenca',"
				+ " accounting_required = true, special_taxpayer_code = '999', withholding_agent_code = '7',"
				+ " rimpe_regime = 'EMPRENDEDOR' WHERE id = ?", s.taxpayer());
		jdbc.update("UPDATE establishments SET address = 'Loja' WHERE id = ?", s.establishment());

		InvoiceXmlData data = documents.xmlData(s.tenant(), id).orElseThrow();
		assertThat(data.issuerLegalName()).isEqualTo("Comercial Andina S.A.");
		assertThat(data.issuerTradeName()).isEqualTo("Andina");
		assertThat(data.headOfficeAddress()).isEqualTo("Av. Amazonas N34-12, Quito");
		assertThat(data.establishmentAddress()).isEqualTo("Av. 10 de Agosto, Quito");
		assertThat(data.accountingRequired()).isFalse();
		assertThat(data.specialTaxpayerCode()).isNull();
		assertThat(data.withholdingAgentCode()).isNull();
		assertThat(data.rimpeRegime()).isEqualTo("NONE");
		assertThat(data.issuerRuc()).isEqualTo(s.ruc());
		assertThat(data.environment()).isEqualTo("1");
		assertThat(data.establishmentCode()).isEqualTo("001");
		assertThat(data.emissionPointCode()).isEqualTo("001");
		assertThat(data.sequence()).isEqualTo(1);
		assertThat(data.accessKey()).hasSize(49);
		assertThat(data.customerIdType()).isEqualTo("05");
		assertThat(data.customerIdNumber()).isEqualTo("1712345678");
		assertThat(data.customerName()).isEqualTo("María Pérez");
		assertThat(data.customerAddress()).isEqualTo("Av. República, Quito");
		assertThat(data.subtotal()).isEqualByComparingTo("20.00");
		assertThat(data.total()).isEqualByComparingTo("23.00");
		assertThat(data.taxes()).hasSize(1);
		assertThat(data.lines()).hasSize(1);
		assertThat(data.lines().get(0).quantity()).isEqualByComparingTo(new BigDecimal("2"));
		assertThat(data.payments()).hasSize(1);
		assertThat(documents.xmlData(UUID.randomUUID(), id)).isEmpty();
	}

	@Test
	void documentFreezesFiscalLegendsAndProviderRuc() throws Exception {
		String owner = newUserToken();
		UUID tenant = createTenant(owner);
		String taxpayerBody = taxpayerJson(TestData.ruc());
		taxpayerBody = taxpayerBody.substring(0, taxpayerBody.length() - 1)
				+ ",\"accountingRequired\":true,\"specialTaxpayerCode\":\"5368\",\"withholdingAgentCode\":\"1\","
				+ "\"rimpeRegime\":\"EMPRENDEDOR\"}";
		String created = mvc.perform(json(post("/api/v1/taxpayers"), owner, taxpayerBody))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID taxpayer = UUID.fromString(JsonPath.read(created, "$.id"));
		createEmissionPoint(owner, createEstablishment(owner, taxpayer, "001"), "001");
		UUID customer = createCustomer(owner, "05", "1712345678");
		UUID product = createProduct(owner, "SRV-001", "10.00", "4");
		BillingScenario s = new BillingScenario(owner, tenant, taxpayer, null, null, null, customer, product);

		UUID id = issueInvoice(owner, s.invoiceJson("1", "11.50"));

		InvoiceXmlData data = documents.xmlData(tenant, id).orElseThrow();
		assertThat(data.accountingRequired()).isTrue();
		assertThat(data.specialTaxpayerCode()).isEqualTo("5368");
		assertThat(data.withholdingAgentCode()).isEqualTo("1");
		assertThat(data.rimpeRegime()).isEqualTo("EMPRENDEDOR");
		// RUC del proveedor configurado para las pruebas (einvoice.provider.ruc).
		assertThat(data.providerRuc()).isEqualTo("1790099999001");
	}

}
