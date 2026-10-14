package com.example.einvoice.taxpayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/** Establecimientos y puntos de emisión. El emisor o establecimiento padre se busca siempre dentro del tenant. */
@Service
public class EstablishmentService {

	private final TaxpayerService taxpayers;

	private final EstablishmentRepository establishments;

	private final EmissionPointRepository emissionPoints;

	private final AuditLog audit;

	public EstablishmentService(TaxpayerService taxpayers, EstablishmentRepository establishments,
			EmissionPointRepository emissionPoints, AuditLog audit) {
		this.taxpayers = taxpayers;
		this.establishments = establishments;
		this.emissionPoints = emissionPoints;
		this.audit = audit;
	}

	@Transactional
	public Establishment createEstablishment(TenantAccess access, UUID taxpayerId, String code, String address) {
		access.requireWrite();
		Taxpayer taxpayer = taxpayers.get(access, taxpayerId);
		Establishment establishment;
		try {
			establishment = establishments
				.saveAndFlush(new Establishment(access.tenantId(), taxpayer.getId(), code, address.strip()));
		}
		catch (DataIntegrityViolationException ex) {
			if (TaxpayerService.violates(ex, "establishments_taxpayer_id_code_key")) {
				throw new ApiException(HttpStatus.CONFLICT, "ESTABLISHMENT_CODE_TAKEN",
						"El emisor ya tiene un establecimiento con ese código");
			}
			throw ex;
		}
		audit.record(access.tenantId(), access.userId(), "ESTABLISHMENT_CREATED", "ESTABLISHMENT",
				establishment.getId(), Map.of("taxpayerId", taxpayer.getId().toString(), "code", code));
		return establishment;
	}

	@Transactional(readOnly = true)
	public List<Establishment> listEstablishments(TenantAccess access, UUID taxpayerId) {
		Taxpayer taxpayer = taxpayers.get(access, taxpayerId);
		return establishments.findAllByTaxpayerIdAndTenantIdOrderByCode(taxpayer.getId(), access.tenantId());
	}

	@Transactional
	public EmissionPoint createEmissionPoint(TenantAccess access, UUID establishmentId, String code) {
		access.requireWrite();
		Establishment establishment = establishment(access, establishmentId);
		EmissionPoint point;
		try {
			point = emissionPoints.saveAndFlush(new EmissionPoint(access.tenantId(), establishment.getId(), code));
		}
		catch (DataIntegrityViolationException ex) {
			if (TaxpayerService.violates(ex, "emission_points_establishment_id_code_key")) {
				throw new ApiException(HttpStatus.CONFLICT, "EMISSION_POINT_CODE_TAKEN",
						"El establecimiento ya tiene un punto de emisión con ese código");
			}
			throw ex;
		}
		audit.record(access.tenantId(), access.userId(), "EMISSION_POINT_CREATED", "EMISSION_POINT", point.getId(),
				Map.of("establishmentId", establishment.getId().toString(), "code", code));
		return point;
	}

	@Transactional(readOnly = true)
	public List<EmissionPoint> listEmissionPoints(TenantAccess access, UUID establishmentId) {
		Establishment establishment = establishment(access, establishmentId);
		return emissionPoints.findAllByEstablishmentIdAndTenantIdOrderByCode(establishment.getId(),
				access.tenantId());
	}

	private Establishment establishment(TenantAccess access, UUID establishmentId) {
		return establishments.findByIdAndTenantId(establishmentId, access.tenantId())
			.orElseThrow(ApiException::notFound);
	}

}
