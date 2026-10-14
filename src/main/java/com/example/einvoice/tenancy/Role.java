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

	/**
	 * Si quien tiene este rol puede dar (o quitar) aquel a otra persona: nadie concede un rol igual o
	 * superior al suyo, y el de propietario no se concede.
	 */
	public boolean canGrant(Role other) {
		return switch (this) {
			case OWNER -> other != OWNER;
			case ADMIN -> other == BILLER || other == AUDITOR;
			default -> false;
		};
	}

	/** Quién registra clientes y emite comprobantes. */
	public boolean canBill() {
		return this == OWNER || this == ADMIN || this == BILLER;
	}

}
