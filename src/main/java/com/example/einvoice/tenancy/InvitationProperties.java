package com.example.einvoice.tenancy;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ttl cuánto vale una invitación desde que se crea
 * @param maxPending cuántas puede tener pendientes una empresa a la vez
 * @param allowWithoutEmail si un usuario cuyo token no trae correo puede aceptar solo con el enlace.
 * Por defecto no: el enlace valdría para cualquiera que lo tuviera
 */
@ConfigurationProperties("einvoice.invitations")
public record InvitationProperties(Duration ttl, int maxPending, boolean allowWithoutEmail) {

	public InvitationProperties {
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new IllegalArgumentException("einvoice.invitations.ttl debe ser un plazo positivo");
		}
		if (maxPending < 1) {
			throw new IllegalArgumentException("einvoice.invitations.max-pending debe ser al menos 1");
		}
	}

}
