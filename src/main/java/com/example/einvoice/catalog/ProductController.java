package com.example.einvoice.catalog;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.Decimals;
import com.example.einvoice.web.PageResponse;
import com.example.einvoice.web.TextRules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

	private final ProductService service;

	public ProductController(ProductService service) {
		this.service = service;
	}

	record CreateProductRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9._-]{1,25}") String sku,
			@NotBlank @Size(max = 300) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String name,
			@NotNull @Pattern(regexp = Decimals.QUANTITY_OR_PRICE) String unitPrice,
			@NotNull @Pattern(regexp = "[0-9]{1,4}") String ivaRateCode) {
	}

	record ProductResponse(UUID id, String sku, String name, String unitPrice, String ivaRateCode) {

		static ProductResponse of(Product product) {
			return new ProductResponse(product.getId(), product.getSku(), product.getName(),
					Decimals.format(product.getUnitPrice(), 6), product.getIvaRateCode());
		}

	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ProductResponse create(TenantAccess access, @Valid @RequestBody CreateProductRequest request) {
		return ProductResponse.of(service.create(access, request.sku(), request.name(),
				Decimals.parse(request.unitPrice(), 12, 6), request.ivaRateCode()));
	}

	@GetMapping
	PageResponse<ProductResponse> list(TenantAccess access, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String q) {
		return PageResponse.of(service.search(access, q, PageResponse.request(page, size)), ProductResponse::of);
	}

	@GetMapping("/{id}")
	ProductResponse get(TenantAccess access, @PathVariable UUID id) {
		return ProductResponse.of(service.get(access, id));
	}

	@PutMapping("/{id}")
	ProductResponse update(TenantAccess access, @PathVariable UUID id,
			@Valid @RequestBody CreateProductRequest request) {
		return ProductResponse.of(service.update(access, id, request.sku(), request.name(),
				Decimals.parse(request.unitPrice(), 12, 6), request.ivaRateCode()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(TenantAccess access, @PathVariable UUID id) {
		service.delete(access, id);
	}

}
