package com.example.einvoice.certification;

import java.util.List;

/**
 * La matriz de facturas con la que se certifica el sistema (docs/12): cada tipo de identificación
 * del adquirente, IVA 15 %, IVA 0 % y ambos a la vez, descuento y más de una forma de pago. Los
 * importes están fijados a mano: el total que calcula el sistema debe coincidir con el esperado.
 */
final class CertificationMatrix {

	/** Tipos de identificación del adquirente (ficha técnica, Tabla 6). */
	static final String RUC = "04";

	static final String CEDULA = "05";

	static final String PASSPORT = "06";

	static final String FINAL_CONSUMER = "07";

	/** Códigos de tarifa de IVA (ficha técnica, Tabla 17). */
	static final String IVA_0 = "0";

	static final String IVA_15 = "4";

	/** Formas de pago (ficha técnica, Tabla 24). */
	static final String CASH = "01";

	static final String FINANCIAL_SYSTEM = "20";

	record Line(String quantity, String unitPrice, String discount, String ivaRateCode) {
	}

	record Payment(String methodCode, String amount) {
	}

	record Case(String name, String buyerIdType, String buyerIdNumber, List<Line> lines, List<Payment> payments,
			String expectedTotal) {
	}

	private CertificationMatrix() {
	}

	/**
	 * @param buyerRuc RUC de un adquirente válido para el SRI
	 * @param buyerCedula cédula de un adquirente válida para el SRI, o nulo para omitir esos casos
	 */
	static List<Case> cases(String buyerRuc, String buyerCedula) {
		return List.of(
				new Case("Factura a un RUC, IVA 15 %", RUC, buyerRuc, List.of(new Line("1", "100.00", "0.00", IVA_15)),
						List.of(new Payment(FINANCIAL_SYSTEM, "115.00")), "115.00"),
				new Case("Factura a una cédula, con descuento", CEDULA, buyerCedula,
						List.of(new Line("3", "10.00", "5.00", IVA_15)), List.of(new Payment(CASH, "28.75")), "28.75"),
				new Case("Factura a un pasaporte, IVA 0 %", PASSPORT, "AB123456",
						List.of(new Line("2", "12.50", "0.00", IVA_0)), List.of(new Payment(FINANCIAL_SYSTEM, "25.00")),
						"25.00"),
				new Case("Factura a consumidor final", FINAL_CONSUMER, "9999999999999",
						List.of(new Line("1", "20.00", "0.00", IVA_15)), List.of(new Payment(CASH, "23.00")), "23.00"),
				new Case("Factura con IVA 15 % e IVA 0 %", CEDULA, buyerCedula,
						List.of(new Line("1", "10.00", "0.00", IVA_15), new Line("2", "4.00", "0.00", IVA_0)),
						List.of(new Payment(FINANCIAL_SYSTEM, "19.50")), "19.50"),
				new Case("Factura con dos formas de pago", RUC, buyerRuc, List.of(new Line("1", "40.00", "0.00", IVA_15)),
						List.of(new Payment(CASH, "16.00"), new Payment(FINANCIAL_SYSTEM, "30.00")), "46.00"));
	}

}
