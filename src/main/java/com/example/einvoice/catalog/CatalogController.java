package com.example.einvoice.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.catalog.CatalogRepository.CodeName;
import com.example.einvoice.catalog.CatalogRepository.PaymentMethod;
import com.example.einvoice.web.Decimals;

/** Catálogos de solo lectura para cualquier usuario autenticado; no dependen del tenant. */
@RestController
@RequestMapping("/api/v1/catalogs")
public class CatalogController {

	private final CatalogRepository catalogs;

	public CatalogController(CatalogRepository catalogs) {
		this.catalogs = catalogs;
	}

	record TaxRateResponse(String taxCode, String rateCode, String description, String rate) {
	}

	@GetMapping("/identification-types")
	List<CodeName> identificationTypes() {
		return catalogs.identificationTypes();
	}

	@GetMapping("/payment-methods")
	List<PaymentMethod> paymentMethods() {
		return catalogs.paymentMethods();
	}

	@GetMapping("/tax-rates")
	List<TaxRateResponse> taxRates() {
		return catalogs.taxRates()
			.stream()
			.map(rate -> new TaxRateResponse(rate.taxCode(), rate.rateCode(), rate.description(),
					Decimals.format(rate.rate(), 2)))
			.toList();
	}

}
