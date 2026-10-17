package com.example.einvoice.catalog;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.web.LikeTerms;

@Service
public class ProductService {

	private final ProductRepository products;

	private final CatalogRepository catalogs;

	private final AuditLog audit;

	public ProductService(ProductRepository products, CatalogRepository catalogs, AuditLog audit) {
		this.products = products;
		this.catalogs = catalogs;
		this.audit = audit;
	}

	@Transactional
	public Product create(TenantAccess access, String sku, String name, BigDecimal unitPrice, String ivaRateCode) {
		access.requireProductCreation();
		if (catalogs.ivaRate(ivaRateCode).isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TAX_RATE_UNKNOWN",
					"La tarifa de IVA no existe en el catálogo");
		}
		Product product;
		try {
			product = products
				.saveAndFlush(new Product(access.tenantId(), sku, name.strip(), unitPrice, ivaRateCode));
		}
		catch (DataIntegrityViolationException ex) {
			if (CustomerService.mentions(ex, "products_sku_key")) {
				throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_SKU_TAKEN", "Ya existe un producto con ese código");
			}
			throw ex;
		}
		audit.record(access, "PRODUCT_CREATED", "PRODUCT", product.getId(),
				Map.of("sku", sku));
		return product;
	}

	@Transactional(readOnly = true)
	public Page<Product> search(TenantAccess access, String term, Pageable pageable) {
		return products.search(access.tenantId(), LikeTerms.escape(term), pageable);
	}

	@Transactional(readOnly = true)
	public Product get(TenantAccess access, UUID id) {
		return products.findByIdAndTenantId(id, access.tenantId()).orElseThrow(ApiException::notFound);
	}

}
