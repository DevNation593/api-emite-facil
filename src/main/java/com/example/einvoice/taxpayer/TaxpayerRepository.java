package com.example.einvoice.taxpayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Los servicios consultan siempre con el tenant. Los métodos heredados que no lo llevan (findById,
 * findAll) no deben usarse para atender peticiones.
 */
public interface TaxpayerRepository extends JpaRepository<Taxpayer, UUID> {

	Optional<Taxpayer> findByIdAndTenantId(UUID id, UUID tenantId);

	List<Taxpayer> findAllByTenantIdOrderByLegalName(UUID tenantId);

}
