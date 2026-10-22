package com.example.einvoice.xml;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Todo lo que el XML de una factura necesita, tal como quedó congelado en el documento al emitirse.
 * rimpeRegime es NONE, EMPRENDEDOR o NEGOCIO_POPULAR; los campos opcionales pueden ser nulos.
 */
public record InvoiceXmlData(String environment, String issuerLegalName, String issuerTradeName, String issuerRuc,
		String accessKey, String documentType, String establishmentCode, String emissionPointCode, int sequence,
		String headOfficeAddress, String withholdingAgentCode, String rimpeRegime, LocalDate issueDate,
		String establishmentAddress, String specialTaxpayerCode, boolean accountingRequired, String customerIdType,
		String customerName, String customerIdNumber, String customerAddress, BigDecimal subtotal,
		BigDecimal totalDiscount, List<Tax> taxes, BigDecimal total, List<Line> lines, List<Payment> payments,
		String providerRuc) {

	public record Tax(String taxCode, String rateCode, BigDecimal rate, BigDecimal base, BigDecimal amount) {
	}

	public record Line(String mainCode, String description, BigDecimal quantity, BigDecimal unitPrice,
			BigDecimal discount, BigDecimal subtotal, String taxCode, String taxRateCode, BigDecimal taxRate,
			BigDecimal taxAmount) {
	}

	/** term y timeUnit (plazo y unidadTiempo) van juntos o ambos nulos. */
	public record Payment(String methodCode, BigDecimal amount, BigDecimal term, String timeUnit) {
	}

}
