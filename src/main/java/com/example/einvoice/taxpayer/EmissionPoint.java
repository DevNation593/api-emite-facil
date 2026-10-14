package com.example.einvoice.taxpayer;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Punto de emisión de un establecimiento. El código es texto de 3 dígitos. */
@Entity
@Table(name = "emission_points")
public class EmissionPoint {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID tenantId;

	private UUID establishmentId;

	private String code;

	private boolean enabled;

	protected EmissionPoint() {
	}

	public EmissionPoint(UUID tenantId, UUID establishmentId, String code) {
		this.tenantId = tenantId;
		this.establishmentId = establishmentId;
		this.code = code;
		this.enabled = true;
	}

	public UUID getId() {
		return id;
	}

	public UUID getEstablishmentId() {
		return establishmentId;
	}

	public String getCode() {
		return code;
	}

	public boolean isEnabled() {
		return enabled;
	}

}
