package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.example.einvoice.xml.DocumentType;

/**
 * Sobre congelado común a todos los comprobantes: lo que el documento guardó al emitirse, sin lo propio
 * de cada tipo. Es {@code InvoiceXmlData} sin las líneas, los impuestos y los pagos. totals y modified
 * pueden ser nulos; recipient es el receptor (comprador, proveedor, sujeto retenido o transportista).
 */
public record DocumentEnvelope(DocumentType type, String environment, Issuer issuer, String accessKey,
		String establishmentCode, String emissionPointCode, int sequence, LocalDate issueDate,
		Recipient recipient, Totals totals, ModifiedDocument modified, String providerRuc) {

	/** rimpeRegime es NONE, EMPRENDEDOR o NEGOCIO_POPULAR; los códigos opcionales y phone pueden ser nulos. */
	public record Issuer(String legalName, String tradeName, String ruc, String headOfficeAddress,
			String establishmentAddress, boolean accountingRequired, String specialTaxpayerCode,
			String withholdingAgentCode, String rimpeRegime, String phone) {
	}

	/** email y phone pueden ser nulos. */
	public record Recipient(String idType, String idNumber, String name, String address, String email,
			String phone) {
	}

	/** total no nulo; subtotal, totalDiscount y totalTax pueden serlo (retención). */
	public record Totals(BigDecimal subtotal, BigDecimal totalDiscount, BigDecimal totalTax, BigDecimal total) {
	}

	/** documentId nulo cuando el comprobante modificado se escribió a mano. */
	public record ModifiedDocument(UUID documentId, String code, String number, LocalDate issueDate) {
	}

	/** Número del comprobante: establecimiento, punto y secuencial de nueve dígitos ("001-002-000000123"). */
	public String number() {
		return establishmentCode + "-" + emissionPointCode + "-" + "%09d".formatted(sequence);
	}

}
