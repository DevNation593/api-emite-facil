package com.example.einvoice.taxpayer;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Establecimiento de un emisor. El código es texto de 3 dígitos, tal como va en el comprobante. */
@Entity
@Table(name = "establishments")
public class Establishment {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID tenantId;

	private UUID taxpayerId;

	private String code;

	private String address;

	protected Establishment() {
	}

	public Establishment(UUID tenantId, UUID taxpayerId, String code, String address) {
		this.tenantId = tenantId;
		this.taxpayerId = taxpayerId;
		this.code = code;
		this.address = address;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTaxpayerId() {
		return taxpayerId;
	}

	public String getCode() {
		return code;
	}

	public String getAddress() {
		return address;
	}

}
