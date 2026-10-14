package com.example.einvoice.catalog;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Producto o servicio de un tenant. La tarifa de IVA es un código del catálogo, no un porcentaje. */
@Entity
@Table(name = "products")
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID tenantId;

	private String sku;

	private String name;

	@Column(precision = 18, scale = 6)
	private BigDecimal unitPrice;

	private String ivaRateCode;

	protected Product() {
	}

	public Product(UUID tenantId, String sku, String name, BigDecimal unitPrice, String ivaRateCode) {
		this.tenantId = tenantId;
		this.sku = sku;
		this.name = name;
		this.unitPrice = unitPrice;
		this.ivaRateCode = ivaRateCode;
	}

	public UUID getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	public String getIvaRateCode() {
		return ivaRateCode;
	}

}
