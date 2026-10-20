package com.example.einvoice.billing;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.einvoice.catalog.CatalogRepository;
import com.example.einvoice.web.ApiException;

/** Monto máximo de un comprobante a consumidor final: lo comparten la factura y la nota de débito. */
@Component
public class FinalConsumerLimit {

	private final CatalogRepository catalogs;

	private final BigDecimal limit;

	public FinalConsumerLimit(CatalogRepository catalogs,
			@Value("${einvoice.invoice.final-consumer-limit}") BigDecimal limit) {
		this.catalogs = catalogs;
		this.limit = limit;
	}

	/** 422 FINAL_CONSUMER_LIMIT_EXCEEDED si el comprador es consumidor final y el total supera el límite. */
	public void check(String identificationType, BigDecimal total) {
		boolean finalConsumer = catalogs.identificationType(identificationType)
			.map(CatalogRepository.IdentificationType::finalConsumer)
			.orElse(false);
		if (finalConsumer && total.compareTo(limit) > 0) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FINAL_CONSUMER_LIMIT_EXCEEDED",
					"Una venta a consumidor final no puede superar el monto máximo; identifique al adquirente");
		}
	}

}
