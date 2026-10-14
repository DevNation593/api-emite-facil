package com.example.einvoice.catalog;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

	Optional<Customer> findByIdAndTenantId(UUID id, UUID tenantId);

	/**
	 * Busca por nombre (contiene, sin distinguir mayúsculas) o por identificación (empieza por). El
	 * término llega ya escapado para LIKE; vacío devuelve todos.
	 */
	@Query("""
			SELECT c FROM Customer c
			WHERE c.tenantId = :tenantId
			  AND (:term = '' OR lower(c.name) LIKE lower(concat('%', :term, '%')) ESCAPE '\\'
			       OR c.idNumber LIKE concat(:term, '%') ESCAPE '\\')
			ORDER BY c.name, c.id
			""")
	Page<Customer> search(UUID tenantId, String term, Pageable pageable);

}
