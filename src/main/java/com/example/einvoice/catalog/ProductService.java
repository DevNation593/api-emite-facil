package com.example.einvoice.catalog;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
		validateRate(ivaRateCode);
		Product product;
		try {
			product = products
				.saveAndFlush(new Product(access.tenantId(), sku, name.strip(), unitPrice, ivaRateCode));
		}
		catch (DataIntegrityViolationException ex) {
			throw duplicateOrOriginal(ex);
		}
		audit.record(access, "PRODUCT_CREATED", "PRODUCT", product.getId(),
				Map.of("sku", sku));
		return product;
	}

	@Transactional
	public Product update(TenantAccess access, UUID id, String sku, String name, BigDecimal unitPrice,
			String ivaRateCode) {
		access.requireProductCreation();
		Product product = get(access, id);
		validateRate(ivaRateCode);
		product.update(sku, name.strip(), unitPrice, ivaRateCode);
		try {
			products.saveAndFlush(product);
		}
		catch (DataIntegrityViolationException ex) {
			throw duplicateOrOriginal(ex);
		}
		catch (OptimisticLockingFailureException ex) {
			throw changed();
		}
		audit.record(access, "PRODUCT_UPDATED", "PRODUCT", id, Map.of("sku", sku));
		return product;
	}

	@Transactional
	public void delete(TenantAccess access, UUID id) {
		access.requireProductCreation();
		Product product = get(access, id);
		product.archive();
		try {
			products.saveAndFlush(product);
		}
		catch (OptimisticLockingFailureException ex) {
			throw changed();
		}
		audit.record(access, "PRODUCT_DELETED", "PRODUCT", id, Map.of("sku", product.getSku()));
	}

	private static ApiException changed() {
		return new ApiException(HttpStatus.CONFLICT, "CATALOG_CHANGED", "El producto cambió. Recargue la lista e inténtelo de nuevo");
	}

	private void validateRate(String ivaRateCode) {
		if (catalogs.ivaRate(ivaRateCode).isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TAX_RATE_UNKNOWN",
					"La tarifa de IVA no existe en el catálogo");
		}
	}

	private static RuntimeException duplicateOrOriginal(DataIntegrityViolationException ex) {
		if (CustomerService.mentions(ex, "products_sku_key")) {
			return new ApiException(HttpStatus.CONFLICT, "PRODUCT_SKU_TAKEN", "Ya existe un producto con ese código");
		}
		return ex;
	}

	@Transactional(readOnly = true)
	public Page<Product> search(TenantAccess access, String term, Pageable pageable) {
		return products.search(access.tenantId(), LikeTerms.escape(term), pageable);
	}

	@Transactional(readOnly = true)
	public Product get(TenantAccess access, UUID id) {
		return products.findByIdAndTenantIdAndDeletedAtIsNull(id, access.tenantId())
			.orElseThrow(ApiException::notFound);
	}

}
