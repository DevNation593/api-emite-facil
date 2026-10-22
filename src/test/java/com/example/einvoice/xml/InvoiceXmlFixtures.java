package com.example.einvoice.xml;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Documento fijo para las pruebas del XML: dos líneas, dos tarifas de IVA y dos pagos. */
public final class InvoiceXmlFixtures {

	/** Vector del doc 06: 07/10/2026, RUC 1790012345001, ambiente 1, 001-002, secuencial 123. */
	public static final String ACCESS_KEY = "0710202601179001234500110010020000001231234567816";

	private InvoiceXmlFixtures() {
	}

	public static BigDecimal d(String value) {
		return new BigDecimal(value);
	}

	public static InvoiceXmlData standard() {
		return builder().build();
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Permite variar un dato del documento fijo sin repetir los veintisiete campos en cada prueba. */
	public static final class Builder {

		public String legalName = "Comercial Andina S.A.";

		public String tradeName = "Andina";

		public String withholdingAgentCode;

		public String rimpeRegime = "NONE";

		public String establishmentAddress = "Av. 10 de Agosto, Quito";

		public String specialTaxpayerCode;

		public boolean accountingRequired;

		public String customerAddress = "Av. República, Quito";

		public String providerRuc;

		public List<InvoiceXmlData.Payment> payments = List.of(new InvoiceXmlData.Payment("20", d("30.00"), null, null),
				new InvoiceXmlData.Payment("01", d("7.00"), null, null));

		public List<InvoiceXmlData.Line> lines = new ArrayList<>(List.of(
				new InvoiceXmlData.Line("SRV-001", "Servicio de consultoría", d("2"), d("10.00"), d("0.00"),
						d("20.00"), "2", "4", d("15.00"), d("3.00")),
				new InvoiceXmlData.Line("LIB-001", "Libro", d("1.5"), d("9.333333"), d("0.00"), d("14.00"), "2", "0",
						d("0.00"), d("0.00"))));

		public InvoiceXmlData build() {
			return new InvoiceXmlData("1", legalName, tradeName, "1790012345001", ACCESS_KEY, "01", "001", "002", 123,
					"Av. Amazonas N34-12, Quito", withholdingAgentCode, rimpeRegime, LocalDate.of(2026, 10, 7),
					establishmentAddress, specialTaxpayerCode, accountingRequired, "05", "María Pérez", "1712345678",
					customerAddress, d("34.00"), d("0.00"),
					List.of(new InvoiceXmlData.Tax("2", "0", d("0.00"), d("14.00"), d("0.00")),
							new InvoiceXmlData.Tax("2", "4", d("15.00"), d("20.00"), d("3.00"))),
					d("37.00"), lines, payments, providerRuc);
		}

	}

}
