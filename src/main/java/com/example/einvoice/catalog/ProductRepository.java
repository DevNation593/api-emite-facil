package com.example.einvoice.catalog;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, UUID> {

	Optional<Product> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

	/** Busca por nombre o código (contiene, sin distinguir mayúsculas). El término llega escapado para LIKE. */
	@Query("""
			SELECT p FROM Product p
			WHERE p.tenantId = :tenantId AND p.deletedAt IS NULL
			  AND (:term = '' OR lower(p.name) LIKE lower(concat('%', :term, '%')) ESCAPE '\\'
			       OR lower(p.sku) LIKE lower(concat('%', :term, '%')) ESCAPE '\\')
			ORDER BY p.name, p.sku
			""")
	Page<Product> search(UUID tenantId, String term, Pageable pageable);

}
