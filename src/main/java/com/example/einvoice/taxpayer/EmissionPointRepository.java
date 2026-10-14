package com.example.einvoice.taxpayer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmissionPointRepository extends JpaRepository<EmissionPoint, UUID> {

	List<EmissionPoint> findAllByEstablishmentIdAndTenantIdOrderByCode(UUID establishmentId, UUID tenantId);

}
