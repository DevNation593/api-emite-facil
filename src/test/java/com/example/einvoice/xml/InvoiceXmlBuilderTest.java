package com.example.einvoice.xml;

import static com.example.einvoice.xml.InvoiceXmlFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** Formato de factura versión 1.1.0 (ficha técnica v2.34, Anexo 3). */
class InvoiceXmlBuilderTest {

	static final String EXPECTED = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
			+ "<factura id=\"comprobante\" version=\"1.1.0\">"
			+ "<infoTributaria><ambiente>1</ambiente><tipoEmision>1</tipoEmision>"
			+ "<razonSocial>Comercial Andina S.A.</razonSocial><nombreComercial>Andina</nombreComercial>"
			+ "<ruc>1790012345001</ruc><claveAcceso>0710202601179001234500110010020000001231234567816</claveAcceso>"
			+ "<codDoc>01</codDoc><estab>001</estab><ptoEmi>002</ptoEmi><secuencial>000000123</secuencial>"
			+ "<dirMatriz>Av. Amazonas N34-12, Quito</dirMatriz></infoTributaria>"
			+ "<infoFactura><fechaEmision>07/10/2026</fechaEmision>"
			+ "<dirEstablecimiento>Av. 10 de Agosto, Quito</dirEstablecimiento>"
			+ "<obligadoContabilidad>NO</obligadoContabilidad>"
			+ "<tipoIdentificacionComprador>05</tipoIdentificacionComprador>"
			+ "<razonSocialComprador>María Pérez</razonSocialComprador>"
			+ "<identificacionComprador>1712345678</identificacionComprador>"
			+ "<direccionComprador>Av. República, Quito</direccionComprador>"
			+ "<totalSinImpuestos>34.00</totalSinImpuestos><totalDescuento>0.00</totalDescuento>"
			+ "<totalConImpuestos>"
			+ "<totalImpuesto><codigo>2</codigo><codigoPorcentaje>0</codigoPorcentaje>"
			+ "<baseImponible>14.00</baseImponible><valor>0.00</valor></totalImpuesto>"
			+ "<totalImpuesto><codigo>2</codigo><codigoPorcentaje>4</codigoPorcentaje>"
			+ "<baseImponible>20.00</baseImponible><valor>3.00</valor></totalImpuesto>"
			+ "</totalConImpuestos><propina>0.00</propina><importeTotal>37.00</importeTotal><moneda>DOLAR</moneda>"
			+ "<pagos><pago><formaPago>20</formaPago><total>30.00</total></pago>"
			+ "<pago><formaPago>01</formaPago><total>7.00</total></pago></pagos></infoFactura>"
			+ "<detalles>"
			+ "<detalle><codigoPrincipal>SRV-001</codigoPrincipal><descripcion>Servicio de consultoría</descripcion>"
			+ "<cantidad>2.000000</cantidad><precioUnitario>10.000000</precioUnitario><descuento>0.00</descuento>"
			+ "<precioTotalSinImpuesto>20.00</precioTotalSinImpuesto><impuestos><impuesto><codigo>2</codigo>"
			+ "<codigoPorcentaje>4</codigoPorcentaje><tarifa>15.00</tarifa><baseImponible>20.00</baseImponible>"
			+ "<valor>3.00</valor></impuesto></impuestos></detalle>"
			+ "<detalle><codigoPrincipal>LIB-001</codigoPrincipal><descripcion>Libro</descripcion>"
			+ "<cantidad>1.500000</cantidad><precioUnitario>9.333333</precioUnitario><descuento>0.00</descuento>"
			+ "<precioTotalSinImpuesto>14.00</precioTotalSinImpuesto><impuestos><impuesto><codigo>2</codigo>"
			+ "<codigoPorcentaje>0</codigoPorcentaje><tarifa>0.00</tarifa><baseImponible>14.00</baseImponible>"
			+ "<valor>0.00</valor></impuesto></impuestos></detalle>" + "</detalles></factura>";

	final XsdValidator validator = new XsdValidator(new InvoiceSchemas());

	static String xml(InvoiceXmlData data) {
		return new String(InvoiceXmlBuilder.build(data), StandardCharsets.UTF_8);
	}

	void assertValid(InvoiceXmlData data) {
		byte[] bytes = InvoiceXmlBuilder.build(data);
		ValidationResult result = validator.validate(bytes, InvoiceXmlBuilder.VERSION);
		assertThat(result.errors()).isEmpty();
		assertThat(InvoiceEnvelopeRules.check(bytes, InvoiceXmlBuilder.VERSION)).isEmpty();
	}

	@Test
	void producesExpectedXml() {
		assertThat(xml(InvoiceXmlFixtures.standard())).isEqualTo(EXPECTED);
		assertThat(InvoiceXmlBuilder.VERSION).isEqualTo(SchemaVersion.V1_1_0);
	}

	@Test
	void resultIsValidAgainstXsdAndRules() {
		assertValid(InvoiceXmlFixtures.standard());
	}

	@Test
	void omitsOptionalElementsWithoutValue() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.tradeName = null;
		builder.establishmentAddress = null;
		builder.customerAddress = " ";
		InvoiceXmlData data = builder.build();
		assertThat(xml(data)).doesNotContain("nombreComercial", "dirEstablecimiento", "direccionComprador",
				"contribuyenteEspecial", "agenteRetencion", "contribuyenteRimpe", "infoAdicional");
		assertValid(data);
	}

	@Test
	void includesFiscalLegendsInXsdOrder() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.accountingRequired = true;
		builder.specialTaxpayerCode = "5368";
		builder.withholdingAgentCode = "1";
		builder.rimpeRegime = "EMPRENDEDOR";
		InvoiceXmlData data = builder.build();
		assertThat(xml(data))
			.contains("<dirMatriz>Av. Amazonas N34-12, Quito</dirMatriz><agenteRetencion>1</agenteRetencion>"
					+ "<contribuyenteRimpe>CONTRIBUYENTE RÉGIMEN RIMPE</contribuyenteRimpe></infoTributaria>")
			.contains("<contribuyenteEspecial>5368</contribuyenteEspecial>"
					+ "<obligadoContabilidad>SI</obligadoContabilidad>");
		assertValid(data);
	}

	@Test
	void negocioPopularUsesItsOwnLegend() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.rimpeRegime = "NEGOCIO_POPULAR";
		assertThat(xml(builder.build()))
			.contains("<contribuyenteRimpe>CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE</contribuyenteRimpe>");
		builder.rimpeRegime = "OTRO";
		assertThatThrownBy(() -> InvoiceXmlBuilder.build(builder.build())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void addsProviderRucAsAdditionalField() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.providerRuc = "1790099999001";
		InvoiceXmlData data = builder.build();
		assertThat(xml(data)).endsWith("</detalles><infoAdicional>"
				+ "<campoAdicional nombre=\"RUC Proveedor\">1790099999001</campoAdicional></infoAdicional></factura>");
		assertValid(data);
	}

	@Test
	void escapesSpecialCharacters() throws Exception {
		String name = "Ñandú & Hijos <\"S.A.\"> 'O'Brien' ✅ 😀";
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.legalName = name;
		builder.lines = new ArrayList<>(List.of(new InvoiceXmlData.Line("SRV-001", "Café & té <frío>", d("2"),
				d("10.00"), d("0.00"), d("20.00"), "2", "4", d("15.00"), d("3.00")), builder.lines.get(1)));
		InvoiceXmlData data = builder.build();
		byte[] bytes = InvoiceXmlBuilder.build(data);

		assertThat(xml(data)).contains("Ñandú &amp; Hijos &lt;").doesNotContain("& Hijos");
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		Document parsed = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
		assertThat(parsed.getElementsByTagName("razonSocial").item(0).getTextContent()).isEqualTo(name);
		assertThat(parsed.getElementsByTagName("descripcion").item(0).getTextContent()).isEqualTo("Café & té <frío>");
		assertValid(data);
	}

	@Test
	void rejectsCharactersThatXmlCannotCarry() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		builder.legalName = "Comercial" + (char) 0 + "Andina";
		assertThatThrownBy(() -> InvoiceXmlBuilder.build(builder.build())).isInstanceOf(IllegalArgumentException.class);
		builder.legalName = "Comercial" + (char) 7 + "Andina";
		assertThatThrownBy(() -> InvoiceXmlBuilder.build(builder.build())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sameInputGivesSameBytes() {
		assertThat(InvoiceXmlBuilder.build(InvoiceXmlFixtures.standard()))
			.isEqualTo(InvoiceXmlBuilder.build(InvoiceXmlFixtures.standard()));
	}

	@Test
	void staysUnderSizeLimitFor500Lines() {
		InvoiceXmlFixtures.Builder builder = InvoiceXmlFixtures.builder();
		InvoiceXmlData.Line line = builder.lines.get(0);
		builder.lines = new ArrayList<>();
		for (int i = 0; i < 500; i++) {
			builder.lines.add(line);
		}
		// Límite de envío individual de la ficha: 320 KB por comprobante.
		assertThat(InvoiceXmlBuilder.build(builder.build()).length).isLessThan(320 * 1024);
	}

}
