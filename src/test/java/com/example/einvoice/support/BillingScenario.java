package com.example.einvoice.support;

import java.util.UUID;

/**
 * Empresa lista para facturar: emisor con establecimiento 001 y punto de emisión 001, un cliente con
 * cédula y un producto de 10.00 con IVA del 15 % (código 4).
 */
public record BillingScenario(String owner, UUID tenant, UUID taxpayer, String ruc, UUID establishment, UUID point,
		UUID customer, UUID product) {

	/** Cuerpo de POST /invoices con una línea del producto del escenario y un pago por el importe dado. */
	public String invoiceJson(String quantity, String paymentAmount) {
		return invoiceJson(customer, "{\"productId\":\"" + product + "\",\"quantity\":\"" + quantity + "\"}",
				paymentAmount);
	}

	public String invoiceJson(UUID customerId, String linesJson, String paymentAmount) {
		return "{\"taxpayerId\":\"" + taxpayer + "\",\"establishmentCode\":\"001\",\"emissionPointCode\":\"001\","
				+ "\"customerId\":\"" + customerId + "\",\"lines\":[" + linesJson + "],"
				+ "\"payments\":[{\"methodCode\":\"20\",\"amount\":\"" + paymentAmount + "\"}]}";
	}

}
