package com.example.einvoice.taxpayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EstablishmentRepository extends JpaRepository<Establishment, UUID> {

	Optional<Establishment> findByIdAndTenantId(UUID id, UUID tenantId);

	List<Establishment> findAllByTaxpayerIdAndTenantIdOrderByCode(UUID taxpayerId, UUID tenantId);

}
