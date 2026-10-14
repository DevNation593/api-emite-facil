package com.example.einvoice.catalog;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cliente (adquirente) de un tenant, identificado por tipo y número según la tabla 6 de la ficha. */
@Entity
@Table(name = "customers")
public class Customer {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID tenantId;

	private String idType;

	private String idNumber;

	private String name;

	private String email;

	private String address;

	private String phone;

	protected Customer() {
	}

	public Customer(UUID tenantId, String idType, String idNumber, String name, String email, String address,
			String phone) {
		this.tenantId = tenantId;
		this.idType = idType;
		this.idNumber = idNumber;
		this.name = name;
		this.email = email;
		this.address = address;
		this.phone = phone;
	}

	public UUID getId() {
		return id;
	}

	public String getIdType() {
		return idType;
	}

	public String getIdNumber() {
		return idNumber;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public String getAddress() {
		return address;
	}

	public String getPhone() {
		return phone;
	}

}
