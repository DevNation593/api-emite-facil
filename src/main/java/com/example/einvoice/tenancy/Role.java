package com.example.einvoice.tenancy;

/**
 * Rol de un usuario dentro de un tenant. Solo OWNER y ADMIN modifican la configuración tributaria;
 * BILLER además factura; AUDITOR solo lee.
 */
public enum Role {

	OWNER, ADMIN, BILLER, AUDITOR;

	public boolean canWrite() {
		return this == OWNER || this == ADMIN;
	}

	/** Quién registra clientes y emite comprobantes. */
	public boolean canBill() {
		return this == OWNER || this == ADMIN || this == BILLER;
	}

}
